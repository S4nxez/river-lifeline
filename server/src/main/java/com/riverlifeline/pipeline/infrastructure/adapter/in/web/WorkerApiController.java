package com.riverlifeline.pipeline.infrastructure.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import com.riverlifeline.pipeline.application.exception.InvalidRequestException;
import com.riverlifeline.pipeline.application.exception.InvalidResultException;
import com.riverlifeline.pipeline.application.port.in.WorkerJobs;
import com.riverlifeline.pipeline.application.port.in.WorkerJobs.RasterDownload;
import com.riverlifeline.pipeline.domain.model.job.LeasedJob;
import com.riverlifeline.pipeline.infrastructure.json.ContractJson;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The internal worker API (api/worker-api.yaml), on the backend's internal address only: the public proxy forwards
 * {@code /api/*} and nothing else. Every request carries {@code X-Worker-Token} ({@link WorkerTokenFilter}). JSON keys
 * are camelCase, as the contract writes them, unlike the public API.
 */
@RestController
@RequestMapping(WorkerApiController.BASE)
@ConditionalOnProperty(prefix = "river.pipeline", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkerApiController {
    public static final String BASE = "/internal/worker";
    private static final int BUFFER = 64 * 1024;

    private final WorkerJobs jobs;
    private final ObjectMapper json = ContractJson.mapper();

    public WorkerApiController(WorkerJobs jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/jobs/next")
    public ResponseEntity<String> next(@RequestParam String types, @RequestParam String contract)
            throws JsonProcessingException {
        Set<Integer> versions = new LinkedHashSet<>();
        for (String version : list(contract)) {
            try {
                versions.add(Integer.parseInt(version));
            } catch (NumberFormatException e) {
                throw new InvalidRequestException("contract must list version numbers: " + contract);
            }
        }
        Optional<LeasedJob> job = jobs.next(list(types), versions);
        if (job.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        ObjectNode body = json.createObjectNode()
                .put("id", job.get().lease().value())
                .put("type", job.get().type().wire())
                .put("contractVersion", job.get().contractVersion())
                .put("leaseSeconds", job.get().leaseSeconds());
        body.putRawValue("inputs", new RawValue(job.get().inputsJson()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
    }

    @PostMapping("/jobs/{jobId}/heartbeat")
    public ResponseEntity<String> heartbeat(@PathVariable String jobId) throws JsonProcessingException {
        int seconds = jobs.heartbeat(jobId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(json.createObjectNode().put("leaseSeconds", seconds)));
    }

    @GetMapping("/rasters/{rasterId}")
    public ResponseEntity<StreamingResponseBody> raster(
            @PathVariable String rasterId,
            @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding) {
        Optional<RasterDownload> download = jobs.raster(rasterId);
        if (download.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean gzip = acceptsGzip(acceptEncoding);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
        if (gzip) {
            response.header(HttpHeaders.CONTENT_ENCODING, "gzip");
        } else {
            response.contentLength(download.get().byteLength());
        }
        RasterDownload body = download.get();
        return response.body(out -> {
            try (InputStream in = gzip ? body.openGzip() : new GZIPInputStream(body.openGzip(), BUFFER)) {
                in.transferTo(out);
            }
        });
    }

    @PutMapping("/jobs/{jobId}/rasters/{name}")
    public ResponseEntity<Void> uploadRaster(
            @PathVariable String jobId,
            @PathVariable String name,
            @RequestHeader(value = HttpHeaders.CONTENT_ENCODING, required = false) String contentEncoding,
            HttpServletRequest request)
            throws IOException {
        InputStream body = request.getInputStream();
        if (contentEncoding != null && contentEncoding.strip().equalsIgnoreCase("gzip")) {
            body = new GZIPInputStream(body, BUFFER);
        } else if (contentEncoding != null
                && !contentEncoding.isBlank()
                && !contentEncoding.strip().equalsIgnoreCase("identity")) {
            throw new InvalidRequestException("Unsupported Content-Encoding: " + contentEncoding);
        }
        jobs.uploadRaster(jobId, name, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/jobs/{jobId}/result")
    public ResponseEntity<Void> result(@PathVariable String jobId, @RequestBody String body)
            throws JsonProcessingException {
        JsonNode data = object(body).get("data");
        if (data == null || !data.isObject()) {
            throw new InvalidResultException("data must be an object");
        }
        jobs.complete(jobId, json.writeValueAsString(data));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/jobs/{jobId}/failure")
    public ResponseEntity<Void> failure(@PathVariable String jobId, @RequestBody String body) {
        JsonNode failure = object(body);
        JsonNode reason = failure.get("reason");
        JsonNode retryable = failure.get("retryable");
        if (reason == null || !reason.isTextual() || retryable == null || !retryable.isBoolean()) {
            throw new InvalidRequestException("A failure needs reason (string) and retryable (boolean)");
        }
        jobs.fail(jobId, reason.asText(), retryable.asBoolean());
        return ResponseEntity.noContent().build();
    }

    private JsonNode object(String body) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) {
                throw new InvalidRequestException("The body must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("The body is not JSON: " + e.getOriginalMessage());
        }
    }

    private static Set<String> list(String commaSeparated) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : commaSeparated.split(",")) {
            if (!value.isBlank()) {
                values.add(value.strip());
            }
        }
        return values;
    }

    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) {
            return false;
        }
        for (String coding : acceptEncoding.split(",")) {
            String[] parts = coding.strip().split(";");
            if (parts[0].strip().equalsIgnoreCase("gzip")) {
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i].strip().replace(" ", "");
                    if (p.equals("q=0") || p.equals("q=0.0") || p.equals("q=0.00") || p.equals("q=0.000")) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }
}

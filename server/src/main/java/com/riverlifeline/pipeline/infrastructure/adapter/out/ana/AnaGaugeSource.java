package com.riverlifeline.pipeline.infrastructure.adapter.out.ana;

import com.riverlifeline.pipeline.application.port.out.GaugeSource;
import com.riverlifeline.pipeline.domain.model.source.GaugeReading;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.HttpCalls;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** River level readings of an ANA telemetry station ({@code DadosHidrometeorologicos}, XML). */
public class AnaGaugeSource implements GaugeSource {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** The row element, as ANA spells it. */
    static final String ROW = "DadosHidrometereologicos";

    private final HttpCalls http;
    private final String telemetryUrl;

    public AnaGaugeSource(HttpCalls http, String telemetryUrl) {
        this.http = http;
        this.telemetryUrl = telemetryUrl;
    }

    @Override
    public List<GaugeReading> readings(String station, LocalDate from, LocalDate to) throws IOException {
        URI uri = URI.create(telemetryUrl + "?codEstacao=" + station + "&dataInicio=" + from.format(DAY) + "&dataFim="
                + to.format(DAY));
        return parse(http.get(uri));
    }

    static List<GaugeReading> parse(byte[] xml) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        List<GaugeReading> readings = new ArrayList<>();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            String when = null;
            String level = null;
            String element = null;
            StringBuilder text = new StringBuilder();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    element = reader.getLocalName();
                    text.setLength(0);
                    if (ROW.equals(element)) {
                        when = null;
                        level = null;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("DataHora".equals(name)) {
                        when = text.toString().strip();
                    } else if ("Nivel".equals(name)) {
                        level = text.toString().strip();
                    } else if (ROW.equals(name)) {
                        add(readings, when, level);
                    }
                    text.setLength(0);
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("Unreadable ANA answer: " + e.getMessage(), e);
        }
        return readings;
    }

    private static void add(List<GaugeReading> readings, String when, String level) {
        if (when == null || when.length() < 10 || level == null || level.isEmpty()) {
            return;
        }
        try {
            readings.add(new GaugeReading(LocalDate.parse(when.substring(0, 10)), Double.parseDouble(level)));
        } catch (RuntimeException e) {
            // A malformed row is skipped; the others still count
        }
    }
}

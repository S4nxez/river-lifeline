package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.AssessmentRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.GaugeRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.PassRow;
import java.util.List;
import java.util.Map;

/** Reads the worker's results the pipeline stored. One implementation reads MySQL; tests use an in-memory one. */
public interface PipelineReader {
    /** The assessed passes of an area that are shown (enough coverage), oldest first. */
    List<PassRow> passes(String areaId);

    /** Every community route of the area's passes. */
    List<AssessmentRow> assessments(String areaId);

    /** The whole-record class of each community of the area (empty before the first classification). */
    Map<String, String> communityClasses(String areaId);

    /** The daily levels of a gauge, oldest first. */
    List<GaugeRow> gauge(String stationCode);
}

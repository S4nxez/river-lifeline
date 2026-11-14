package com.riverlifeline.pipeline.domain.model.source;

import java.time.LocalDate;

/** One telemetry reading of a gauge: the day it was taken and the level in centimetres. */
public record GaugeReading(LocalDate day, double levelCm) {}

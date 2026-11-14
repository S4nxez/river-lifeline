package com.riverlifeline.pipeline.domain.model.raster;

/** The value that means "no data": a number, NaN (float rasters) or none (every value is data). */
public sealed interface NoData {
    NoData NAN = new NotANumber();
    NoData NONE = new None();

    static NoData value(double value) {
        return new Value(value);
    }

    /** {@code "NaN"}, a number or null: how the contract and the database write it. */
    String text();

    static NoData parse(String text) {
        if (text == null) {
            return NONE;
        }
        if ("NaN".equals(text)) {
            return NAN;
        }
        return value(Double.parseDouble(text));
    }

    record Value(double value) implements NoData {
        public Value {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("A numeric nodata must be finite: " + value);
            }
        }

        @Override
        public String text() {
            return value == Math.rint(value) && Math.abs(value) < 1e15
                    ? Long.toString((long) value)
                    : Double.toString(value);
        }
    }

    record NotANumber() implements NoData {
        @Override
        public String text() {
            return "NaN";
        }
    }

    record None() implements NoData {
        @Override
        public String text() {
            return null;
        }
    }
}

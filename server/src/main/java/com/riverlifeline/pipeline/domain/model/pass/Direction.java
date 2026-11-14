package com.riverlifeline.pipeline.domain.model.pass;

/** Orbit direction of a pass. */
public enum Direction {
    ASCENDING("ascending", 'A'),
    DESCENDING("descending", 'D');

    private final String wire;
    private final char letter;

    Direction(String wire, char letter) {
        this.wire = wire;
        this.letter = letter;
    }

    public String wire() {
        return wire;
    }

    public char letter() {
        return letter;
    }

    public static Direction fromLetter(char letter) {
        for (Direction direction : values()) {
            if (direction.letter == letter) {
                return direction;
            }
        }
        throw new IllegalArgumentException("Unknown orbit direction: " + letter);
    }

    public static Direction fromWire(String value) {
        for (Direction direction : values()) {
            if (direction.wire.equals(value)) {
                return direction;
            }
        }
        throw new IllegalArgumentException("Unknown orbit direction: " + value);
    }
}

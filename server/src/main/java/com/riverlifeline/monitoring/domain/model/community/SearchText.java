package com.riverlifeline.monitoring.domain.model.community;

import java.text.Normalizer;
import java.util.Locale;

/** Case- and accent-insensitive text: {@code "Alvarães"} and {@code "alvaraes"} match. */
public final class SearchText {
    private SearchText() {}

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String withoutMarks = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return withoutMarks.toLowerCase(Locale.ROOT).strip();
    }

    public static boolean contains(String text, String query) {
        return normalize(text).contains(normalize(query));
    }
}

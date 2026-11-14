package com.nisarsar.common;

import java.util.List;

public class Constants {
    public static final String API = "/api";
    public static final String COMMUNITIES = "/communities";
    public static final String PASSES = "/passes";

    /** Read-only endpoints anyone can call: the public monitor. Everything else requires authentication. */
    public static final List<String> PUBLIC_PATHS = List.of(API + COMMUNITIES, API + PASSES);

    public Constants() {}
}

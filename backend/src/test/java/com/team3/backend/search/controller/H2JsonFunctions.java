package com.team3.backend.search.controller;

import tools.jackson.databind.json.JsonMapper;

public final class H2JsonFunctions {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private H2JsonFunctions() {
    }

    public static String extract(String json, String key) {
        return JSON_MAPPER.readTree(json).path(key).asString();
    }
}

package com.luky.nexusmind.model;

import java.util.Locale;

public enum ParseEngine {
    AUTO,
    TIKA,
    MINERU;

    public static ParseEngine fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return ParseEngine.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AUTO;
        }
    }
}

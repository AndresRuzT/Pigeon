package io.github.andres.pigeon.domain.vo;

import io.github.andres.pigeon.domain.exception.SensitiveDataException;

import java.util.Objects;
import java.util.regex.Pattern;

public record MaskedCardNumber(String value) {
    private static final Pattern MASKED_PATTERN = Pattern.compile("^\\*{4}\\s?\\d{4}$");
    private static final Pattern LAST_4_PATTERN = Pattern.compile("^\\d{4}$");

    public MaskedCardNumber {
        Objects.requireNonNull(value, "MaskedCardNumber cannot be null");
        String trimmed = value.trim();
        if (LAST_4_PATTERN.matcher(trimmed).matches()) {
            value = "**** " + trimmed;
        } else if (MASKED_PATTERN.matcher(trimmed).matches()) {
            value = trimmed.contains(" ") ? trimmed : "**** " + trimmed.substring(4);
        } else {
            throw new SensitiveDataException("Invalid masked card number: must be either 4 digits or '**** XXXX'");
        }
    }

    public static MaskedCardNumber ofLast4(String last4) {
        return new MaskedCardNumber(last4);
    }

    public String last4() {
        return value.substring(value.length() - 4);
    }
}

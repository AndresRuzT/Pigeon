package io.github.andres.pigeon.infrastructure.adapter.in.rest.validation;

import io.github.andres.pigeon.infrastructure.adapter.in.rest.dto.IngestEventRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NoSensitiveDataValidator implements ConstraintValidator<NoSensitiveData, Object> {

    // Matches contiguous runs of 10 or more digits
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\b\\d{10,}\\b");

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        if (value instanceof IngestEventRequest request) {
            return validateData(request.data(), context);
        } else if (value instanceof Map<?, ?> map) {
            return validateMap(map, context);
        } else if (value instanceof String str) {
            return validateString(str, context);
        }

        return true;
    }

    private boolean validateData(Map<String, Object> data, ConstraintValidatorContext context) {
        if (data == null) {
            return true;
        }
        return validateMap(data, context);
    }

    private boolean validateMap(Map<?, ?> map, ConstraintValidatorContext context) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String s && !validateString(s, context)) {
                return false;
            } else if (val instanceof Map<?, ?> nested && !validateMap(nested, context)) {
                return false;
            }
        }
        return true;
    }

    private boolean validateString(String s, ConstraintValidatorContext context) {
        // Strip hyphens and spaces to check for formatted card numbers
        String digitsOnly = s.replaceAll("[\\s-]", "");

        // Check if string contains any 13-19 digit sequence passing Luhn
        if (containsLuhnCardNumber(digitsOnly)) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Sensitive data detected: full credit/debit card numbers are strictly forbidden")
                    .addConstraintViolation();
            return false;
        }

        // Check for continuous digit runs of 10 or more digits
        Matcher matcher = LONG_DIGIT_RUN.matcher(s);
        if (matcher.find()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Sensitive data detected: long account numbers (>9 digits) are strictly forbidden")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }

    public static boolean containsLuhnCardNumber(String input) {
        // Extract candidate sequences of 13 to 19 digits
        Pattern candidatePattern = Pattern.compile("\\d{13,19}");
        Matcher matcher = candidatePattern.matcher(input);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (passesLuhn(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static boolean passesLuhn(String cardNumber) {
        int sum = 0;
        boolean alternate = false;
        for (int i = cardNumber.length() - 1; i >= 0; i--) {
            int n = Integer.parseInt(cardNumber.substring(i, i + 1));
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n = (n % 10) + 1;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }
}

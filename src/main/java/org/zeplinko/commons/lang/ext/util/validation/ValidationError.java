package org.zeplinko.commons.lang.ext.util.validation;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class ValidationError {
    private final String path;

    private final String code;

    private final String message;

    private final Map<String, Object> attributes;

    public ValidationError(String path, String code, String message) {
        this(path, code, message, new HashMap<>());
    }

    public ValidationError(String path, String code, String message, Map<String, ?> attributes) {
        Validator.requireNonBlank(path);
        Validator.requireNonBlank(code);
        Objects.requireNonNull(message);
        Objects.requireNonNull(attributes);

        this.path = path;
        this.code = code;
        this.message = message;
        this.attributes = Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    public String getPath() {
        return path;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getAttributes() {
        return this.attributes;
    }
}

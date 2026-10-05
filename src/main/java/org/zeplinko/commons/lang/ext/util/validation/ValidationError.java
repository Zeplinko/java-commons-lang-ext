package org.zeplinko.commons.lang.ext.util.validation;

public class ValidationError {
    private final String path;

    private final String code;

    private final String message;

    public ValidationError(String path, String code, String message) {
        this.path = path;
        this.code = code;
        this.message = message;
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
}

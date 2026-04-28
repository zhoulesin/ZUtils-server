package com.zutils.server.exception;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resource, String key) {
        super(404, resource + " not found: " + key);
    }
}

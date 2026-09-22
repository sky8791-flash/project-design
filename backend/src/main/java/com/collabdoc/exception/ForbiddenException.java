package com.collabdoc.exception;

/** The caller may not perform this operation on this document. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}

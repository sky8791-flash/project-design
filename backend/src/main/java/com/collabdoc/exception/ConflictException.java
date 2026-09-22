package com.collabdoc.exception;

/** The caller's base version is older than the document's current sequence. */
public class ConflictException extends RuntimeException {

    private final int currentVersion;

    public ConflictException(int currentVersion) {
        super("Version conflict: document is at " + currentVersion);
        this.currentVersion = currentVersion;
    }

    public int getCurrentVersion() { return currentVersion; }
}

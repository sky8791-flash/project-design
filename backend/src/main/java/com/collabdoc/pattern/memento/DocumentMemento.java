package com.collabdoc.pattern.memento;

import java.time.LocalDateTime;

public class DocumentMemento {

    private final String content;
    private final String contentFormat;
    private final int version;
    private final LocalDateTime timestamp;

    public DocumentMemento(String content, String contentFormat, int version) {
        this.content = content;
        this.contentFormat = contentFormat;
        this.version = version;
        this.timestamp = LocalDateTime.now();
    }

    public String getContent() { return content; }
    public String getContentFormat() { return contentFormat; }
    public int getVersion() { return version; }
    public LocalDateTime getTimestamp() { return timestamp; }
}

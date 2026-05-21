package com.collabdoc.pattern.memento;

import java.time.LocalDateTime;

public class DocumentMemento {

    private final String content;
    private final int version;
    private final LocalDateTime timestamp;

    public DocumentMemento(String content, int version) {
        this.content = content;
        this.version = version;
        this.timestamp = LocalDateTime.now();
    }

    public String getContent() { return content; }
    public int getVersion() { return version; }
    public LocalDateTime getTimestamp() { return timestamp; }
}

package com.collabdoc.pattern.command;

public interface Command {

    String getType();

    String getParams();

    void execute(StringBuilder documentContent);

    void undo(StringBuilder documentContent);
}

package com.collabdoc.pattern.command;

public class DeleteCommand implements Command {

    private final int position;
    private final int length;
    private String deletedText;

    public DeleteCommand(int position, int length) {
        this.position = position;
        this.length = length;
    }

    @Override
    public String getType() {
        return "DELETE";
    }

    @Override
    public String getParams() {
        return "{\"position\":" + position + ",\"length\":" + length + "}";
    }

    @Override
    public void execute(StringBuilder documentContent) {
        int pos = Math.min(position, documentContent.length());
        int end = Math.min(pos + length, documentContent.length());
        deletedText = documentContent.substring(pos, end);
        documentContent.delete(pos, end);
    }

    @Override
    public void undo(StringBuilder documentContent) {
        if (deletedText != null) {
            int pos = Math.min(position, documentContent.length());
            documentContent.insert(pos, deletedText);
        }
    }

    public int getPosition() { return position; }
    public int getLength() { return length; }
    public String getDeletedText() { return deletedText; }
}

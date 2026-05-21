package com.collabdoc.pattern.command;

public class InsertCommand implements Command {

    private final int position;
    private final String text;

    public InsertCommand(int position, String text) {
        this.position = position;
        this.text = text;
    }

    @Override
    public String getType() {
        return "INSERT";
    }

    @Override
    public String getParams() {
        return "{\"position\":" + position + ",\"text\":\"" + text.replace("\"", "\\\"") + "\"}";
    }

    @Override
    public void execute(StringBuilder documentContent) {
        int pos = Math.min(position, documentContent.length());
        documentContent.insert(pos, text);
    }

    @Override
    public void undo(StringBuilder documentContent) {
        int pos = Math.min(position, documentContent.length());
        int end = Math.min(pos + text.length(), documentContent.length());
        documentContent.delete(pos, end);
    }

    public int getPosition() { return position; }
    public String getText() { return text; }
}

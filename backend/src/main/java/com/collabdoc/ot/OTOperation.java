package com.collabdoc.ot;

public class OTOperation {

    public enum Type { INSERT, DELETE }

    private final Type type;
    private final int position;
    private final String text;
    private final int length;
    private int baseVersion;

    public OTOperation(Type type, int position, String text, int length) {
        this.type = type;
        this.position = position;
        this.text = text;
        this.length = length;
    }

    public static OTOperation insert(int position, String text) {
        return new OTOperation(Type.INSERT, position, text, 0);
    }

    public static OTOperation delete(int position, int length) {
        return new OTOperation(Type.DELETE, position, null, length);
    }

    public Type getType() { return type; }
    public int getPosition() { return position; }
    public String getText() { return text; }
    public int getLength() { return length; }
    public int getBaseVersion() { return baseVersion; }
    public void setBaseVersion(int baseVersion) { this.baseVersion = baseVersion; }

    public int getEffectLength() {
        return type == Type.INSERT ? (text != null ? text.length() : 0) : length;
    }

    @Override
    public String toString() {
        if (type == Type.INSERT) {
            return "INSERT(pos=" + position + ", text=\"" + text + "\")";
        } else {
            return "DELETE(pos=" + position + ", len=" + length + ")";
        }
    }
}

package com.collabdoc.pattern.command;

public class FormatCommand implements Command {

    private final int startPos;
    private final int endPos;
    private final String formatType;
    private final String formatValue;
    private String previousFormatValue;

    public FormatCommand(int startPos, int endPos, String formatType, String formatValue) {
        this.startPos = startPos;
        this.endPos = endPos;
        this.formatType = formatType;
        this.formatValue = formatValue;
    }

    @Override
    public String getType() {
        return "FORMAT";
    }

    @Override
    public String getParams() {
        return "{\"startPos\":" + startPos + ",\"endPos\":" + endPos +
                ",\"formatType\":\"" + formatType + "\",\"formatValue\":\"" + formatValue + "\"}";
    }

    @Override
    public void execute(StringBuilder documentContent) {
        if (documentContent.length() == 0) return;
        
        int start = Math.min(startPos, documentContent.length());
        int end = Math.min(endPos, documentContent.length());
        
        if (start >= end) return;
        
        String selectedText = documentContent.substring(start, end);
        previousFormatValue = selectedText;
        
        String formattedText = applyFormat(selectedText, formatType, formatValue);
        documentContent.replace(start, end, formattedText);
    }

    @Override
    public void undo(StringBuilder documentContent) {
        if (previousFormatValue == null) return;
        
        int start = Math.min(startPos, documentContent.length());
        int end = Math.min(startPos + previousFormatValue.length(), documentContent.length());
        
        documentContent.replace(start, end, previousFormatValue);
    }

    private String applyFormat(String text, String type, String value) {
        switch (type.toLowerCase()) {
            case "bold":
                return "<strong>" + text + "</strong>";
            case "italic":
                return "<em>" + text + "</em>";
            case "underline":
                return "<u>" + text + "</u>";
            case "strikethrough":
                return "<s>" + text + "</s>";
            case "code":
                return "<code>" + text + "</code>";
            case "highlight":
                return "<mark>" + text + "</mark>";
            case "link":
                return "<a href=\"" + value + "\">" + text + "</a>";
            default:
                return text;
        }
    }

    public int getStartPos() { return startPos; }
    public int getEndPos() { return endPos; }
    public String getFormatType() { return formatType; }
    public String getFormatValue() { return formatValue; }
}

package com.collabdoc.pattern.command;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CommandInvoker {

    private final Map<Long, List<Command>> executedCommandsMap = new ConcurrentHashMap<>();
    private final Map<Long, Integer> currentIndexMap = new ConcurrentHashMap<>();

    public void executeCommand(Command command, StringBuilder documentContent, Long documentId) {
        command.execute(documentContent);
        List<Command> commands = executedCommandsMap.computeIfAbsent(documentId, k -> new ArrayList<>());
        int currentIndex = currentIndexMap.getOrDefault(documentId, -1);
        currentIndex++;
        if (currentIndex < commands.size()) {
            commands.subList(currentIndex, commands.size()).clear();
        }
        commands.add(command);
        currentIndexMap.put(documentId, currentIndex);
    }

    public Command undo(StringBuilder documentContent, Long documentId) {
        List<Command> commands = executedCommandsMap.get(documentId);
        int currentIndex = currentIndexMap.getOrDefault(documentId, -1);
        if (commands == null || currentIndex < 0) {
            return null;
        }
        Command command = commands.get(currentIndex);
        command.undo(documentContent);
        currentIndex--;
        currentIndexMap.put(documentId, currentIndex);
        return command;
    }

    public Command redo(StringBuilder documentContent, Long documentId) {
        List<Command> commands = executedCommandsMap.get(documentId);
        int currentIndex = currentIndexMap.getOrDefault(documentId, -1);
        if (commands == null || currentIndex >= commands.size() - 1) {
            return null;
        }
        currentIndex++;
        Command command = commands.get(currentIndex);
        command.execute(documentContent);
        currentIndexMap.put(documentId, currentIndex);
        return command;
    }

    public void reset(Long documentId) {
        executedCommandsMap.remove(documentId);
        currentIndexMap.remove(documentId);
    }
}

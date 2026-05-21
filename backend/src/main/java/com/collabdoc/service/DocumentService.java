package com.collabdoc.service;

import com.collabdoc.dto.DocumentState;
import com.collabdoc.dto.EditOperation;
import com.collabdoc.dto.OperationLogDTO;
import com.collabdoc.entity.Document;
import com.collabdoc.entity.DocumentShare;
import com.collabdoc.entity.DocumentSnapshot;
import com.collabdoc.entity.OperationLog;
import com.collabdoc.entity.User;
import com.collabdoc.ot.OTOperation;
import com.collabdoc.ot.OperationalTransform;
import com.collabdoc.pattern.command.Command;
import com.collabdoc.pattern.command.CommandInvoker;
import com.collabdoc.pattern.command.DeleteCommand;
import com.collabdoc.pattern.command.InsertCommand;
import com.collabdoc.pattern.memento.DocumentMemento;
import com.collabdoc.pattern.memento.MementoCaretaker;
import com.collabdoc.pattern.observer.DocumentSubject;
import com.collabdoc.repository.DocumentRepository;
import com.collabdoc.repository.DocumentShareRepository;
import com.collabdoc.repository.DocumentSnapshotRepository;
import com.collabdoc.repository.OperationLogRepository;
import com.collabdoc.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Transactional
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final OperationLogRepository operationLogRepository;
    private final DocumentSnapshotRepository snapshotRepository;
    private final DocumentShareRepository documentShareRepository;
    private final UserRepository userRepository;
    private final OperationalTransform ot;
    private final CommandInvoker commandInvoker;
    private final MementoCaretaker mementoCaretaker;
    private final DocumentSubject documentSubject;
    private final ObjectMapper objectMapper;

    private static final int MAX_HISTORY_PER_DOC = 1000;
    private final Map<Long, List<OTOperation>> operationHistories = new ConcurrentHashMap<>();

    public DocumentService(DocumentRepository documentRepository,
                           OperationLogRepository operationLogRepository,
                           DocumentSnapshotRepository snapshotRepository,
                           DocumentShareRepository documentShareRepository,
                           UserRepository userRepository,
                           OperationalTransform ot,
                           CommandInvoker commandInvoker,
                           MementoCaretaker mementoCaretaker,
                           DocumentSubject documentSubject,
                           ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.operationLogRepository = operationLogRepository;
        this.snapshotRepository = snapshotRepository;
        this.documentShareRepository = documentShareRepository;
        this.userRepository = userRepository;
        this.ot = ot;
        this.commandInvoker = commandInvoker;
        this.mementoCaretaker = mementoCaretaker;
        this.documentSubject = documentSubject;
        this.objectMapper = objectMapper;
    }

    public Document createDocument(String title, Long userId) {
        Document doc = new Document(title, "", userId);
        documentRepository.save(doc);
        mementoCaretaker.saveState("", 0, doc.getId());
        return doc;
    }

    public DocumentState getDocumentState(Long documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        int onlineCount = documentSubject.getObserverCount(String.valueOf(documentId));
        return new DocumentState(
            String.valueOf(doc.getId()),
            doc.getContent(),
            doc.getVersion(),
            onlineCount,
            doc.getUpdatedAt()
        );
    }

    public DocumentState applyOperation(EditOperation editOp) {
        Document doc = documentRepository.findById(Long.parseLong(editOp.getDocumentId()))
                .orElseThrow(() -> new RuntimeException("Document not found: " + editOp.getDocumentId()));

        Long docId = doc.getId();
        List<OTOperation> docHistory = operationHistories.computeIfAbsent(docId, k -> new ArrayList<>());

        List<OTOperation> transformed = ot.transformAgainstHistory(editOp.toOTOperation(), docHistory);
        if (transformed.isEmpty() && !docHistory.isEmpty()) {
            throw new RuntimeException("Operation could not be transformed");
        }

        OTOperation finalOp = transformed.isEmpty() ? editOp.toOTOperation() : transformed.get(transformed.size() - 1);
        StringBuilder content = new StringBuilder(doc.getContent());
        Command command = createCommand(finalOp);
        commandInvoker.executeCommand(command, content, docId);

        String newContent = content.toString();
        int newVersion = doc.getVersion() + 1;

        doc.setContent(newContent);
        doc.setVersion(newVersion);
        documentRepository.save(doc);

        docHistory.add(finalOp);
        if (docHistory.size() > MAX_HISTORY_PER_DOC) {
            docHistory.subList(0, docHistory.size() - MAX_HISTORY_PER_DOC).clear();
        }

        saveOperationLog(docId, editOp.getUserId(), finalOp, newVersion);
        mementoCaretaker.saveState(newContent, newVersion, docId);

        documentSubject.notifyAllObservers(
            String.valueOf(docId), newContent, newVersion);

        int onlineCount = documentSubject.getObserverCount(String.valueOf(docId));
        return new DocumentState(
            String.valueOf(docId),
            newContent,
            newVersion,
            onlineCount,
            doc.getUpdatedAt()
        );
    }

    private Command createCommand(OTOperation op) {
        if (op.getType() == OTOperation.Type.INSERT) {
            return new InsertCommand(op.getPosition(), op.getText());
        } else {
            return new DeleteCommand(op.getPosition(), op.getLength());
        }
    }

    private void saveOperationLog(Long documentId, Long userId, OTOperation op, int version) {
        try {
            String params;
            if (op.getType() == OTOperation.Type.INSERT) {
                params = objectMapper.writeValueAsString(
                    Map.of("position", op.getPosition(), "text", op.getText() != null ? op.getText() : ""));
            } else {
                params = objectMapper.writeValueAsString(
                    Map.of("position", op.getPosition(), "length", op.getLength()));
            }
            OperationLog log = new OperationLog(documentId, userId, op.getType().name(), params, version);
            operationLogRepository.save(log);
        } catch (JsonProcessingException e) {
            OperationLog log = new OperationLog(documentId, userId, op.getType().name(), "{}", version);
            operationLogRepository.save(log);
        }
    }

    public List<Document> getUserDocuments(Long userId) {
        return documentRepository.findByCreatedByOrderByUpdatedAtDesc(userId);
    }

    public Document getDocument(Long id) {
        return documentRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Document not found: " + id));
    }

    public DocumentState saveDocument(Long documentId, String content, int baseVersion, Long userId) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));

        doc.setContent(content);
        int newVersion = doc.getVersion() + 1;
        doc.setVersion(newVersion);
        documentRepository.save(doc);

        String params = "{\"action\":\"SAVE\",\"contentLength\":" + content.length() + "}";
        operationLogRepository.save(new OperationLog(documentId, userId, "SAVE", params, newVersion));
        mementoCaretaker.saveState(content, newVersion, documentId);
        documentSubject.notifyAllObservers(String.valueOf(documentId), content, newVersion);

        int onlineCount = documentSubject.getObserverCount(String.valueOf(documentId));
        return new DocumentState(
            String.valueOf(doc.getId()),
            content,
            newVersion,
            onlineCount,
            doc.getUpdatedAt()
        );
    }

    public DocumentState undo(Long documentId) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));

        mementoCaretaker.loadHistoryFromDatabase(documentId);
        DocumentMemento memento = mementoCaretaker.undo(documentId);
        if (memento == null) {
            throw new RuntimeException("Nothing to undo");
        }

        doc.setContent(memento.getContent());
        doc.setVersion(memento.getVersion());
        documentRepository.save(doc);

        documentSubject.notifyAllObservers(String.valueOf(documentId), memento.getContent(), memento.getVersion());

        int onlineCount = documentSubject.getObserverCount(String.valueOf(documentId));
        return new DocumentState(
            String.valueOf(doc.getId()),
            memento.getContent(),
            memento.getVersion(),
            onlineCount,
            doc.getUpdatedAt()
        );
    }

    public DocumentState redo(Long documentId) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));

        DocumentMemento memento = mementoCaretaker.redo(documentId);
        if (memento == null) {
            throw new RuntimeException("Nothing to redo");
        }

        doc.setContent(memento.getContent());
        doc.setVersion(memento.getVersion());
        documentRepository.save(doc);

        documentSubject.notifyAllObservers(String.valueOf(documentId), memento.getContent(), memento.getVersion());

        int onlineCount = documentSubject.getObserverCount(String.valueOf(documentId));
        return new DocumentState(
            String.valueOf(doc.getId()),
            memento.getContent(),
            memento.getVersion(),
            onlineCount,
            doc.getUpdatedAt()
        );
    }

    public List<OperationLogDTO> getOperationHistory(Long documentId) {
        List<OperationLog> logs = operationLogRepository.findByDocumentIdOrderByVersionAsc(documentId);
        
        List<Long> userIds = logs.stream()
            .map(OperationLog::getUserId)
            .distinct()
            .collect(Collectors.toList());
        
        Map<Long, String> usernameMap = userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getId, User::getUsername));
        
        return logs.stream()
            .map(log -> new OperationLogDTO(
                log.getId(),
                log.getDocumentId(),
                log.getUserId(),
                usernameMap.getOrDefault(log.getUserId(), "未知用户"),
                log.getCommandType(),
                log.getCommandParams(),
                log.getVersion(),
                log.getCreatedAt()
            ))
            .collect(Collectors.toList());
    }

    public DocumentState restoreVersion(Long documentId, Integer version) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));

        DocumentSnapshot snapshot = snapshotRepository.findByDocumentIdAndVersion(documentId, version)
            .orElseThrow(() -> new RuntimeException("Snapshot not found for version: " + version));

        doc.setContent(snapshot.getContent());
        doc.setVersion(snapshot.getVersion());
        documentRepository.save(doc);

        documentSubject.notifyAllObservers(String.valueOf(documentId), snapshot.getContent(), snapshot.getVersion());

        int onlineCount = documentSubject.getObserverCount(String.valueOf(documentId));
        return new DocumentState(
            String.valueOf(doc.getId()),
            snapshot.getContent(),
            snapshot.getVersion(),
            onlineCount,
            doc.getUpdatedAt()
        );
    }

    public void deleteDocument(Long documentId, Long userId) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));

        if (!doc.getCreatedBy().equals(userId)) {
            throw new RuntimeException("Only the document owner can delete this document");
        }

        documentShareRepository.findByDocumentId(documentId).forEach(share -> {
            documentShareRepository.delete(share);
        });
        
        snapshotRepository.findByDocumentIdOrderByVersionDesc(documentId).forEach(snapshot -> {
            snapshotRepository.delete(snapshot);
        });
        
        operationLogRepository.findByDocumentIdOrderByVersionAsc(documentId).forEach(log -> {
            operationLogRepository.delete(log);
        });

        documentRepository.delete(doc);
    }

    public DocumentShare shareDocument(Long documentId, Long userId, Long sharedByUserId, DocumentShare.Permission permission) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));

        if (!doc.getCreatedBy().equals(sharedByUserId)) {
            throw new RuntimeException("Only the document owner can share this document");
        }

        if (doc.getCreatedBy().equals(userId)) {
            throw new RuntimeException("Cannot share document with yourself");
        }

        if (documentShareRepository.existsByDocumentIdAndUserId(documentId, userId)) {
            throw new RuntimeException("Document already shared with this user");
        }

        DocumentShare share = new DocumentShare(documentId, userId, permission, sharedByUserId);
        return documentShareRepository.save(share);
    }

    public void removeShare(Long documentId, Long userId, Long requestUserId) {
        Document doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));

        if (!doc.getCreatedBy().equals(requestUserId)) {
            throw new RuntimeException("Only the document owner can remove shares");
        }

        documentShareRepository.deleteByDocumentIdAndUserId(documentId, userId);
    }

    public List<DocumentShare> getDocumentShares(Long documentId) {
        return documentShareRepository.findByDocumentId(documentId);
    }

    public List<Document> getSharedDocuments(Long userId) {
        List<DocumentShare> shares = documentShareRepository.findByUserId(userId);
        return shares.stream()
            .map(share -> documentRepository.findById(share.getDocumentId()).orElse(null))
            .filter(doc -> doc != null)
            .collect(Collectors.toList());
    }

    public boolean hasAccess(Long documentId, Long userId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) return false;
        
        if (doc.getCreatedBy().equals(userId)) return true;
        
        return documentShareRepository.existsByDocumentIdAndUserId(documentId, userId);
    }

    public boolean hasWriteAccess(Long documentId, Long userId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) return false;
        
        if (doc.getCreatedBy().equals(userId)) return true;
        
        return documentShareRepository.findByDocumentIdAndUserId(documentId, userId)
            .map(share -> share.getPermission() == DocumentShare.Permission.READ_WRITE)
            .orElse(false);
    }
}

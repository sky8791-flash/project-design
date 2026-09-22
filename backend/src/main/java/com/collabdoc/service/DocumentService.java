package com.collabdoc.service;

import com.collabdoc.dto.ContentAppliedEvent;
import com.collabdoc.dto.DocumentShareView;
import com.collabdoc.dto.DocumentState;
import com.collabdoc.dto.DocumentSummary;
import com.collabdoc.dto.OperationLogDTO;
import com.collabdoc.dto.ShareNotifiedEvent;
import com.collabdoc.entity.Document;
import com.collabdoc.entity.DocumentShare;
import com.collabdoc.entity.DocumentSnapshot;
import com.collabdoc.entity.Notification;
import com.collabdoc.entity.OperationLog;
import com.collabdoc.entity.User;
import com.collabdoc.exception.ConflictException;
import com.collabdoc.exception.ForbiddenException;
import com.collabdoc.exception.NotFoundException;
import com.collabdoc.pattern.memento.DocumentMemento;
import com.collabdoc.pattern.memento.MementoCaretaker;
import com.collabdoc.repository.DocumentRepository;
import com.collabdoc.repository.DocumentShareRepository;
import com.collabdoc.repository.DocumentSnapshotRepository;
import com.collabdoc.repository.NotificationRepository;
import com.collabdoc.repository.OperationLogRepository;
import com.collabdoc.repository.UserRepository;
import com.collabdoc.websocket.CollabBus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The server is a sequencer, not a content interpreter: clients submit ProseMirror step batches that
 * it orders and stores opaquely, and it only rewrites content itself for whole-document saves.
 *
 * <p>Every accepted write mints {@code version + 1} under the document row lock, and no path ever
 * lowers the version. Frames carry that number so a client that sees a gap can refetch the missing
 * operations.</p>
 */
@Service
@Transactional
public class DocumentService {

    /** A client is asked to upload a full document state every this many committed steps. */
    public static final int CHECKPOINT_EVERY = 200;
    public static final String OWNER_PERMISSION = "OWNER";
    public static final int MAX_TITLE_LENGTH = 200;

    private final DocumentRepository documentRepository;
    private final OperationLogRepository operationLogRepository;
    private final DocumentSnapshotRepository snapshotRepository;
    private final NotificationRepository notificationRepository;
    private final DocumentShareRepository documentShareRepository;
    private final UserRepository userRepository;
    private final MementoCaretaker mementoCaretaker;
    private final CollabBus bus;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    public DocumentService(DocumentRepository documentRepository,
                           OperationLogRepository operationLogRepository,
                           DocumentSnapshotRepository snapshotRepository,
                           NotificationRepository notificationRepository,
                           DocumentShareRepository documentShareRepository,
                           UserRepository userRepository,
                           MementoCaretaker mementoCaretaker,
                           CollabBus bus,
                           ApplicationEventPublisher events,
                           ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.operationLogRepository = operationLogRepository;
        this.snapshotRepository = snapshotRepository;
        this.notificationRepository = notificationRepository;
        this.documentShareRepository = documentShareRepository;
        this.userRepository = userRepository;
        this.mementoCaretaker = mementoCaretaker;
        this.bus = bus;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public Document createDocument(String title, Long userId) {
        Document doc = new Document(title, "", userId);
        Document saved = documentRepository.save(doc);
        mementoCaretaker.capture(saved.getId(), saved.getContent(), saved.getContentFormat(), saved.getVersion());
        return saved;
    }

    @Transactional(readOnly = true)
    public DocumentState getDocumentState(Long documentId) {
        Document doc = requireDocument(documentId);
        return stateOf(doc);
    }

    /** Read that also proves the caller may see the document. */
    @Transactional(readOnly = true)
    public DocumentState getDocumentStateFor(Long documentId, Long userId) {
        return stateOf(requireAccessible(documentId, userId));
    }

    @Transactional(readOnly = true)
    public List<OperationLogDTO> getOperationHistoryFor(Long documentId, Long userId) {
        requireAccessible(documentId, userId);
        return getOperationHistory(documentId);
    }

    private Document requireAccessible(Long documentId, Long userId) {
        Document doc = requireDocument(documentId);
        requireAccess(doc, userId);
        return doc;
    }

    /**
     * Orders one client step batch after the document's current version.
     *
     * @throws ConflictException when {@code baseVersion} is stale; its payload carries the current
     *         version so the client can refetch the gap and rebase.
     */
    public int appendStepBatch(Long documentId, Long userId, int baseVersion, String clientId,
                              JsonNode steps, int docSize, String originSessionId) {
        Document locked = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        requireWriteAccess(locked, userId);
        int current = locked.getVersion();
        if (baseVersion != current) {
            throw new ConflictException(current);
        }

        int seq = current + 1;
        if (documentRepository.casAdvanceVersion(documentId, seq, current, LocalDateTime.now()) == 0) {
            throw new ConflictException(seq);
        }

        ObjectNode params = objectMapper.createObjectNode();
        params.put("baseVersion", baseVersion);
        params.put("clientId", clientId);
        params.put("docSize", docSize);
        params.set("steps", steps);
        operationLogRepository.save(new OperationLog(documentId, userId, "STEPS", params.toString(), seq));

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "STEPS");
        frame.put("documentId", String.valueOf(documentId));
        frame.put("version", seq);
        frame.put("baseVersion", baseVersion);
        frame.put("clientId", clientId);
        frame.put("steps", steps);

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "ACK");
        ack.put("clientId", clientId);
        ack.put("version", seq);
        ack.put("checkpointRequested", seq % CHECKPOINT_EVERY == 0);

        events.publishEvent(new ContentAppliedEvent(documentId, originSessionId, frame, ack));
        return seq;
    }

    /** Whole-document write with conflict detection: the caller's base must still be current. */
    public DocumentState putContent(Long documentId, Long userId, String content, int baseVersion,
                                    String format) {
        String contentFormat = Document.FORMAT_DOC_JSON.equals(format)
                ? Document.FORMAT_DOC_JSON : Document.FORMAT_HTML;
        Document locked = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        requireWriteAccess(locked, userId);
        int current = locked.getVersion();
        if (baseVersion != current) {
            throw new ConflictException(current);
        }

        int seq = current + 1;
        LocalDateTime now = LocalDateTime.now();
        if (documentRepository.casContent(documentId, content, contentFormat, seq, current, now) == 0) {
            throw new ConflictException(seq);
        }

        String params = objectMapper.createObjectNode()
                .put("action", "SAVE")
                .put("contentLength", content.length())
                .toString();
        operationLogRepository.save(new OperationLog(documentId, userId, "SAVE", params, seq));
        // A whole-document write is itself a checkpoint: everything below it is now replayable from it.
        mementoCaretaker.capture(documentId, content, contentFormat, seq);
        operationLogRepository.deleteByDocumentIdUpToVersion(documentId, seq - 1);

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "RESET");
        frame.put("documentId", String.valueOf(documentId));
        frame.put("content", content);
        frame.put("contentFormat", contentFormat);
        frame.put("version", seq);
        events.publishEvent(new ContentAppliedEvent(documentId, null, frame));

        return new DocumentState(String.valueOf(documentId), content, seq,
                bus.onlineCount(String.valueOf(documentId)), now,
                contentFormat, seq);
    }

    /**
     * Folds the operations up to {@code atSeq} into a full document state. This records history rather
     * than writing content: a late checkpoint must not roll a newer version back.
     */
    public void recordCheckpoint(Long documentId, Long userId, int atSeq, String content, String format) {
        Document locked = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        requireWriteAccess(locked, userId);
        if (atSeq > locked.getVersion()) {
            throw new ConflictException(locked.getVersion());
        }

        if (snapshotRepository.findByDocumentIdAndVersion(documentId, atSeq).isEmpty()) {
            mementoCaretaker.capture(documentId, content, format, atSeq);
        }
        operationLogRepository.deleteByDocumentIdUpToVersion(documentId, atSeq);

        if (atSeq == locked.getVersion()) {
            documentRepository.casContent(documentId, content, format, locked.getVersion(),
                    locked.getVersion(), LocalDateTime.now());
        }
    }

    /** Operations the client has not seen yet, oldest first, for closing a sequence gap. */
    @Transactional(readOnly = true)
    public List<OperationLogDTO> getOperationsAfter(Long documentId, Long userId, int afterVersion) {
        requireAccess(requireDocument(documentId), userId);
        return toHistoryDto(operationLogRepository
                .findByDocumentIdAndVersionGreaterThanOrderByVersionAsc(documentId, afterVersion));
    }

    /** Restores a checkpointed version by writing it forward as a new version. */
    public DocumentState restoreVersion(Long documentId, Long userId, int version) {
        Document locked = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        requireWriteAccess(locked, userId);

        // Checked before the lookup so a caller with no access cannot tell "no such version" apart
        // from "version exists but you may not see it".
        DocumentMemento memento = mementoCaretaker.restore(documentId, version);
        if (memento == null) {
            throw new NotFoundException("Snapshot not found for version: " + version);
        }

        int current = locked.getVersion();
        int seq = current + 1;
        LocalDateTime now = LocalDateTime.now();
        if (documentRepository.casContent(documentId, memento.getContent(), memento.getContentFormat(),
                seq, current, now) == 0) {
            throw new ConflictException(seq);
        }

        operationLogRepository.save(new OperationLog(documentId, userId, "RESTORE",
                objectMapper.createObjectNode().put("fromVersion", version).toString(), seq));
        mementoCaretaker.capture(documentId, memento.getContent(), memento.getContentFormat(), seq);
        operationLogRepository.deleteByDocumentIdUpToVersion(documentId, seq - 1);

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "RESET");
        frame.put("documentId", String.valueOf(documentId));
        frame.put("content", memento.getContent());
        frame.put("contentFormat", memento.getContentFormat());
        frame.put("version", seq);
        events.publishEvent(new ContentAppliedEvent(documentId, null, frame));

        return new DocumentState(String.valueOf(documentId), memento.getContent(), seq,
                bus.onlineCount(String.valueOf(documentId)), now,
                memento.getContentFormat());
    }

    /**
     * Audit trail for the history panel. Checkpoints fold the log below them, so each retained snapshot
     * is surfaced as its own entry; otherwise a document older than the last checkpoint would appear to
     * have no history at all.
     */
    @Transactional(readOnly = true)
    public List<OperationLogDTO> getOperationHistory(Long documentId) {
        List<OperationLogDTO> rows = new ArrayList<>(
                toHistoryDto(operationLogRepository.findByDocumentIdOrderByVersionAsc(documentId)));

        snapshotRepository.findByDocumentIdOrderByVersionDesc(documentId).stream()
            .filter(snapshot -> rows.stream().noneMatch(row -> row.getVersion().equals(snapshot.getVersion())))
            .map(snapshot -> {
                OperationLogDTO dto = new OperationLogDTO(null, documentId, null, "系统检查点", "CHECKPOINT",
                        "{\"foldedUpTo\":" + snapshot.getVersion() + "}", snapshot.getVersion(),
                        snapshot.getCreatedAt());
                dto.setSnapshotAvailable(true);
                return dto;
            })
            .forEach(rows::add);

        rows.sort(Comparator.comparing(OperationLogDTO::getVersion));
        return rows;
    }

    private List<OperationLogDTO> toHistoryDto(List<OperationLog> logs) {
        List<Long> userIds = logs.stream().map(OperationLog::getUserId).distinct().collect(Collectors.toList());
        Map<Long, String> usernameMap = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream()
                    .collect(Collectors.toMap(User::getId, User::getUsername));
        // Only checkpointed versions can be restored, so callers need to know which rows carry one.
        Set<Integer> checkpointed = logs.isEmpty() ? Set.of()
                : snapshotRepository.findByDocumentIdOrderByVersionDesc(logs.get(0).getDocumentId()).stream()
                    .map(DocumentSnapshot::getVersion).collect(Collectors.toSet());

        return logs.stream()
            .map(log -> {
                OperationLogDTO dto = new OperationLogDTO(
                log.getId(),
                log.getDocumentId(),
                log.getUserId(),
                usernameMap.getOrDefault(log.getUserId(), "未知用户"),
                log.getCommandType(),
                log.getCommandParams(),
                log.getVersion(),
                log.getCreatedAt()
            );
                dto.setSnapshotAvailable(checkpointed.contains(log.getVersion()));
                return dto;
            })
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DocumentSummary> getUserDocuments(Long userId) {
        List<Document> docs = documentRepository.findByCreatedByOrderByUpdatedAtDesc(userId);
        return docs.stream()
            .map(doc -> new DocumentSummary(String.valueOf(doc.getId()), doc.getTitle(), doc.getVersion(),
                    OWNER_PERMISSION, doc.getCreatedBy(), null, doc.getUpdatedAt()))
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DocumentSummary> getSharedDocuments(Long userId) {
        List<DocumentShare> shares = documentShareRepository.findByUserId(userId);
        if (shares.isEmpty()) return List.of();

        Map<Long, String> permissions = shares.stream().collect(Collectors.toMap(
                DocumentShare::getDocumentId, share -> share.getPermission().name(), (a, b) -> a));
        Map<Long, Document> docs = documentRepository
                .findAllById(new ArrayList<>(permissions.keySet())).stream()
                .collect(Collectors.toMap(Document::getId, doc -> doc));
        Map<Long, String> owners = userRepository
                .findAllById(docs.values().stream().map(Document::getCreatedBy).distinct().collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(User::getId, User::getUsername));

        return shares.stream()
            .map(share -> docs.get(share.getDocumentId()))
            .filter(doc -> doc != null)
            .map(doc -> new DocumentSummary(String.valueOf(doc.getId()), doc.getTitle(), doc.getVersion(),
                    permissions.get(doc.getId()), doc.getCreatedBy(),
                    owners.get(doc.getCreatedBy()), doc.getUpdatedAt()))
            .collect(Collectors.toList());
    }

    /** Share rows joined against the sharee's identity. */
    @Transactional(readOnly = true)
    public List<DocumentShareView> getDocumentShareViews(Long documentId, Long requestUserId) {
        requireAccess(requireDocument(documentId), requestUserId);
        List<DocumentShare> shares = documentShareRepository.findByDocumentId(documentId);
        if (shares.isEmpty()) return List.of();

        Map<Long, User> users = userRepository
                .findAllById(shares.stream().map(DocumentShare::getUserId).distinct().collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(User::getId, user -> user));

        return shares.stream()
            .map(share -> {
                User sharee = users.get(share.getUserId());
                return new DocumentShareView(share.getId(), share.getUserId(),
                        sharee != null ? sharee.getUsername() : "未知用户",
                        sharee != null ? sharee.getUserCode() : null,
                        share.getPermission().name(), share.getSharedBy(), share.getCreatedAt());
            })
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public String displayNameOf(Long userId) {
        if (userId == null) return "anonymous";
        return userRepository.findById(userId).map(User::getUsername).orElse("用户" + userId);
    }

    /** {@code OWNER}, the share permission, or {@code null} when the caller may not see the document. */
    @Transactional(readOnly = true)
    public String permissionOf(Long documentId, Long userId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || userId == null) return null;
        if (userId.equals(doc.getCreatedBy())) return OWNER_PERMISSION;
        return documentShareRepository.findByDocumentIdAndUserId(documentId, userId)
            .map(share -> share.getPermission().name())
            .orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean hasAccess(Long documentId, Long userId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        return doc != null && canAccess(doc, userId);
    }

    private boolean canAccess(Document doc, Long userId) {
        if (userId == null) return false;
        if (doc.getCreatedBy().equals(userId)) return true;
        return documentShareRepository.existsByDocumentIdAndUserId(doc.getId(), userId);
    }

    private boolean canWrite(Document doc, Long userId) {
        if (userId == null) return false;
        if (doc.getCreatedBy().equals(userId)) return true;
        return documentShareRepository.findByDocumentIdAndUserId(doc.getId(), userId)
            .map(share -> share.getPermission() == DocumentShare.Permission.READ_WRITE)
            .orElse(false);
    }

    private void requireAccess(Document doc, Long userId) {
        if (!canAccess(doc, userId)) {
            throw new ForbiddenException("No access to document " + doc.getId());
        }
    }

    private void requireWriteAccess(Document doc, Long userId) {
        if (!canWrite(doc, userId)) {
            throw new ForbiddenException("No write access to document " + doc.getId());
        }
    }

    private Document requireDocument(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
    }

    private DocumentState stateOf(Document doc) {
        // The newest checkpoint is the content a client can start from; operations above it are replayed
        // by the client, because this server never interprets document content itself.
        DocumentState state = snapshotRepository.findTopByDocumentIdOrderByVersionDesc(doc.getId())
            .map(snapshot -> new DocumentState(String.valueOf(doc.getId()), snapshot.getContent(),
                    doc.getVersion(), bus.onlineCount(String.valueOf(doc.getId())),
                    doc.getUpdatedAt(), snapshot.getContentFormat(), snapshot.getVersion()))
            .orElseGet(() -> new DocumentState(String.valueOf(doc.getId()), doc.getContent(),
                    doc.getVersion(), bus.onlineCount(String.valueOf(doc.getId())),
                    doc.getUpdatedAt(), doc.getContentFormat(), doc.getVersion()));
        state.setTitle(doc.getTitle());
        return state;
    }

    public void deleteDocument(Long documentId, Long userId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        if (!userId.equals(doc.getCreatedBy())) {
            throw new ForbiddenException("Only the document owner can delete this document");
        }
        purgeDocument(documentId);
    }

    /** Removes a document and everything hanging off it, with no ownership check. */
    public void purgeDocument(Long documentId) {
        documentShareRepository.deleteByDocumentId(documentId);
        notificationRepository.deleteByDocumentId(documentId);
        operationLogRepository.deleteByDocumentId(documentId);
        snapshotRepository.deleteAll(snapshotRepository.findByDocumentIdOrderByVersionDesc(documentId));
        documentRepository.deleteById(documentId);
    }

    public DocumentShare shareDocument(Long documentId, Long userId, Long sharedByUserId,
                                       DocumentShare.Permission permission) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));

        if (!sharedByUserId.equals(doc.getCreatedBy())) {
            throw new ForbiddenException("Only the document owner can share this document");
        }
        if (userId.equals(doc.getCreatedBy())) {
            throw new IllegalStateException("Cannot share document with yourself");
        }
        if (documentShareRepository.existsByDocumentIdAndUserId(documentId, userId)) {
            throw new IllegalStateException("Document already shared with this user");
        }
        // Without this, a mistyped or deleted id silently creates a share row and a notification
        // addressed to nobody, and the UI reports success.
        userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        DocumentShare saved = documentShareRepository.save(
                new DocumentShare(documentId, userId, permission, sharedByUserId));
        notifyShared(doc, saved, sharedByUserId);
        return saved;
    }

    /**
     * Shares are invisible otherwise: the recipient only sees a new document after reloading the list.
     * The row is persisted first so an offline recipient still finds it on next sign-in.
     */
    private void notifyShared(Document doc, DocumentShare share, Long sharedByUserId) {
        String owner = displayNameOf(sharedByUserId);
        String message = owner + " 已将文档《" + shortTitle(doc.getTitle()) + "》分享给你（"
                + share.getPermission().name() + "）";
        Notification notification = notificationRepository.save(
                new Notification(share.getUserId(), doc.getId(), "SHARE", message));

        events.publishEvent(new ShareNotifiedEvent(share.getUserId(), notification.getId(),
                doc.getId(), message, notification.getCreatedAt()));
    }

    /** {@code notification.message} is VARCHAR(255); a long title must not fail the share insert. */
    private String shortTitle(String title) {
        if (title == null) return "";
        return title.length() <= 80 ? title : title.substring(0, 80) + "…";
    }

    @Transactional(readOnly = true)
    public List<Notification> getNotifications(Long userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public long countUnreadNotifications(Long userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    public void markNotificationsRead(Long userId) {
        notificationRepository.markAllRead(userId);
    }

    public Document rename(Long documentId, Long userId, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title must not be blank");
        }
        if (title.trim().length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        Document locked = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        requireWriteAccess(locked, userId);
        locked.setTitle(title.trim());
        Document saved = documentRepository.save(locked);

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "TITLE_UPDATE");
        frame.put("documentId", String.valueOf(documentId));
        frame.put("title", saved.getTitle());
        events.publishEvent(new ContentAppliedEvent(documentId, null, frame));
        return saved;
    }

    /** Title search across every document the caller can see, newest first. */
    @Transactional(readOnly = true)
    public List<DocumentSummary> searchDocuments(Long userId, String keyword) {
        String needle = keyword == null ? "" : keyword.trim().toLowerCase();
        List<DocumentSummary> visible = new ArrayList<>(getUserDocuments(userId));
        visible.addAll(getSharedDocuments(userId));
        return visible.stream()
            .filter(summary -> needle.isEmpty() || summary.getTitle().toLowerCase().contains(needle))
            .sorted(Comparator.comparing(DocumentSummary::getUpdatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
            .collect(Collectors.toList());
    }

    public void removeShare(Long documentId, Long userId, Long requestUserId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        if (!requestUserId.equals(doc.getCreatedBy())) {
            throw new ForbiddenException("Only the document owner can remove shares");
        }

        documentShareRepository.deleteByDocumentIdAndUserId(documentId, userId);
    }
}

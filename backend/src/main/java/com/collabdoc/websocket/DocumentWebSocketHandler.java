package com.collabdoc.websocket;

import com.collabdoc.dto.DocumentState;
import com.collabdoc.exception.ConflictException;
import com.collabdoc.exception.ForbiddenException;
import com.collabdoc.pattern.observer.DocumentSubject;
import com.collabdoc.pattern.observer.WebSocketObserver;
import com.collabdoc.security.AuthUser;
import com.collabdoc.security.JwtAuthFilter;
import com.collabdoc.service.DocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Socket entry point for one document. Both the document id and the caller's identity come from the
 * connection itself: the id from the path, the user from the JWT presented at handshake, never from a
 * frame payload, so a client cannot write into a document it did not open.
 */
@Component
public class DocumentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentWebSocketHandler.class);
    private static final CloseStatus NO_ACCESS = new CloseStatus(4001, "no access");
    private static final CloseStatus BAD_REQUEST = new CloseStatus(4004, "bad handshake");
    private static final int MAX_STEPS_JSON_CHARS = 64 * 1024;

    private final DocumentService documentService;
    private final DocumentSubject documentSubject;
    private final ObjectMapper objectMapper;
    /** Session bookkeeping only; every frame this handler emits leaves through {@link #bus}. */
    private final WebSocketSessionManager sessionManager;
    private final CollabBus bus;
    private final JwtAuthFilter jwtAuthFilter;
    private final Map<String, Participant> participants = new ConcurrentHashMap<>();

    public DocumentWebSocketHandler(DocumentService documentService,
                                    DocumentSubject documentSubject,
                                    ObjectMapper objectMapper,
                                    WebSocketSessionManager sessionManager,
                                    CollabBus bus,
                                    JwtAuthFilter jwtAuthFilter) {
        this.documentService = documentService;
        this.documentSubject = documentSubject;
        this.objectMapper = objectMapper;
        this.sessionManager = sessionManager;
        this.bus = bus;
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String pathId = extractDocumentId(session);
        AuthUser caller = authenticatedUser(session);
        if (pathId == null || caller == null || !isNumeric(pathId)) {
            session.close(BAD_REQUEST);
            return;
        }

        long docId = Long.parseLong(pathId);
        // Canonical key. The path segment is free text, so /ws/document/007 would otherwise register this
        // session under "007" while every event is keyed String.valueOf(documentId) = "7": the observer, the
        // session map and the membership hash would never meet, the ACK would be dropped, and the tab would
        // stop syncing without a single log line.
        String documentId = String.valueOf(docId);
        if (!documentService.hasAccess(docId, caller.id())) {
            session.close(NO_ACCESS);
            return;
        }

        participants.put(session.getId(), new Participant(documentId, caller.id(), caller.username(),
                bearerToken(session)));
        sessionManager.register(documentId, session, caller.id());
        documentSubject.attach(new WebSocketObserver(bus, documentId));
        bus.sessionJoined(documentId, session.getId());
        try {
            sendInit(session, documentId, docId, caller.id());
        } catch (Exception e) {
            log.error("Failed to send INIT for session {}", session.getId(), e);
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private void sendInit(WebSocketSession session, String documentId, long docId, Long userId) {
        Participant participant = participants.get(session.getId());
        DocumentState state = documentService.getDocumentState(docId);

        Map<String, Object> init = new LinkedHashMap<>();
        init.put("type", "INIT");
        init.put("documentId", documentId);
        init.put("title", state.getTitle());
        init.put("content", state.getContent());
        init.put("contentFormat", state.getContentFormat());
        init.put("version", state.getVersion());
        init.put("checkpointVersion", state.getCheckpointVersion());
        init.put("onlineCount", state.getOnlineCount());
        init.put("permission", documentService.permissionOf(docId, userId));
        bus.deliverLocal(documentId, session.getId(), init);

        Map<String, Object> joined = new LinkedHashMap<>();
        joined.put("type", "USER_JOINED");
        joined.put("userId", String.valueOf(userId));
        joined.put("username", participant.username());
        joined.put("onlineCount", bus.onlineCount(documentId));
        bus.broadcast(documentId, joined, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Participant participant = participants.get(session.getId());
        if (participant == null) return;

        JsonNode payload;
        try {
            payload = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("Malformed frame from session {}: {}", session.getId(), e.getMessage());
            return;
        }
        if (payload == null || !payload.hasNonNull("type")) return;

        switch (payload.get("type").asText()) {
            case "STEP_BATCH" -> handleStepBatch(session, participant, payload);
            case "CURSOR" -> handleCursor(session, participant, payload);
            default -> { }
        }
    }

    private void handleStepBatch(WebSocketSession session, Participant participant, JsonNode payload)
            throws IOException {
        JsonNode steps = payload.get("steps");
        if (steps == null || !steps.isArray() || steps.toString().length() > MAX_STEPS_JSON_CHARS) {
            return;
        }
        // A socket is authenticated once at handshake; without this re-check an account that is disabled
        // or deleted while the tab stays open would keep writing through that connection forever.
        AuthUser current = jwtAuthFilter.authenticate(participant.token());
        if (current == null) {
            log.info("Closing session {}: token no longer valid", session.getId());
            session.close(NO_ACCESS);
            return;
        }
        String clientId = payload.path("clientId").asText(session.getId());

        try {
            documentService.appendStepBatch(
                    Long.parseLong(participant.documentId()),
                    current.id(),
                    payload.path("baseVersion").asInt(),
                    clientId,
                    steps,
                    payload.path("docSize").asInt(),
                    session.getId());
        } catch (ConflictException e) {
            Map<String, Object> reject = new LinkedHashMap<>();
            reject.put("type", "REJECT");
            reject.put("clientId", clientId);
            reject.put("version", e.getCurrentVersion());
            bus.deliverLocal(participant.documentId(), session.getId(), reject);
        } catch (ForbiddenException e) {
            log.info("Closing session {}: {}", session.getId(), e.getMessage());
            session.close(NO_ACCESS);
        }
    }

    private void handleCursor(WebSocketSession session, Participant participant, JsonNode payload) {
        if (!payload.hasNonNull("position")) return;

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "CURSOR_UPDATE");
        frame.put("userId", String.valueOf(participant.userId()));
        frame.put("username", participant.username());
        frame.put("position", payload.get("position").asInt());
        bus.broadcast(participant.documentId(), frame, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Participant participant = participants.remove(session.getId());
        if (participant == null) return;

        // Local bookkeeping first: the observer refcount has to come back down whether or not the bus
        // can be reached, or a dead document stays attached on this node forever.
        sessionManager.unregister(participant.documentId(), session);
        documentSubject.detach(participant.documentId());
        bus.sessionLeft(participant.documentId(), session.getId());

        Map<String, Object> left = new LinkedHashMap<>();
        left.put("type", "USER_LEFT");
        left.put("userId", String.valueOf(participant.userId()));
        left.put("username", participant.username());
        // Measured after sessionLeft, so the number no longer includes the participant who just went.
        // Without it the count never goes down: the client only reads onlineCount from frames that carry it.
        left.put("onlineCount", bus.onlineCount(participant.documentId()));
        bus.broadcast(participant.documentId(), left, null);
    }

    private String extractDocumentId(WebSocketSession session) {
        if (session.getUri() == null) return null;
        String[] parts = session.getUri().getPath().split("/");
        return parts.length == 0 ? null : parts[parts.length - 1];
    }

    private AuthUser authenticatedUser(WebSocketSession session) {
        Object principal = session.getAttributes().get(JwtHandshakeInterceptor.ATTRIBUTE_USER);
        return principal instanceof AuthUser user ? user : null;
    }

    private String bearerToken(WebSocketSession session) {
        Object token = session.getAttributes().get(JwtHandshakeInterceptor.ATTRIBUTE_TOKEN);
        return token instanceof String raw ? raw : null;
    }

    private boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) return false;
        // Also a length bound: Long.parseLong throws for anything over 19 digits, and an exception out of
        // afterConnectionEstablished closes the socket with a generic 1011 instead of the 4004 this is.
        if (value.length() > 18) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private record Participant(String documentId, Long userId, String username, String token) {}
}

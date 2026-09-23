package com.collabdoc.websocket;

import com.collabdoc.dto.ContentAppliedEvent;
import com.collabdoc.dto.OperationLogDTO;
import com.collabdoc.entity.Document;
import com.collabdoc.entity.User;
import com.collabdoc.pattern.observer.DocumentObserver;
import com.collabdoc.pattern.observer.DocumentSubjectImpl;
import com.collabdoc.pattern.observer.WebSocketObserver;
import com.collabdoc.service.DocumentService;
import com.collabdoc.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The properties the collaboration design is supposed to hold by, and that everything else assumes:
 * a client never receives its own content back, a rolled back write reaches nobody, and the log a client
 * replays is a gapless order carrying exactly what its authors submitted.
 *
 * <p>The bus is mocked so delivery is observable as method calls. Where a test asserts "never happens",
 * it asserts on the frame's {@code type} rather than on map equality: the observer broadcasts a copy
 * stamped with {@code onlineCount}, so an {@code equals}-based matcher could never match and the check
 * would pass whatever the production code did.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CollabInvariantTest {

    @MockBean private CollabBus bus;

    @Autowired private DocumentService documentService;
    @Autowired private UserService userService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager txManager;

    private User newUser() {
        String name = "inv-" + UUID.randomUUID().toString().substring(0, 8);
        return userService.createUser(name, name + "@test.local", "pw123456");
    }

    private JsonNode steps(String marker) {
        try {
            return objectMapper.readTree("[{\"stepType\":\"replace\",\"from\":1,\"to\":1,"
                    + "\"slice\":{\"content\":[{\"type\":\"text\",\"text\":\"" + marker + "\"}],"
                    + "\"openStart\":0,\"openEnd\":0}}]");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** H2 hands a JSON column back quoted, MySQL hands back the object; the client sees both. */
    private JsonNode params(String raw) throws Exception {
        JsonNode node = objectMapper.readTree(raw);
        return node.isTextual() ? objectMapper.readTree(node.asText()) : node;
    }

    /** A registered-looking session: the manager keys by id and only writes to open ones. */
    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    /** A content frame is never echoed to its own session, and an ACK is never broadcast to anyone else. */
    @Test
    void broadcastExcludesTheOriginSessionWhileTheAckReachesOnlyTheOrigin() {
        WebSocketObserver observer = new WebSocketObserver(bus, "7");
        Map<String, Object> content = new LinkedHashMap<>(Map.of("type", "STEPS", "version", 3));
        Map<String, Object> ack = new LinkedHashMap<>(Map.of("type", "ACK", "clientId", "c-1", "version", 3));

        observer.update(new ContentAppliedEvent(7L, "session-a", content, ack));

        verify(bus).onlineCount("7");
        verify(bus).broadcast(eq("7"),
                argThat(frame -> "STEPS".equals(frame.get("type"))
                        && Integer.valueOf(3).equals(frame.get("version"))
                        && frame.containsKey("onlineCount")),
                eq("session-a"));
        verify(bus).deliverLocal(eq("7"), eq("session-a"),
                argThat(frame -> "ACK".equals(frame.get("type")) && "c-1".equals(frame.get("clientId"))));
        verify(bus, never()).deliverLocal(anyString(), anyString(),
                argThat(frame -> "STEPS".equals(frame.get("type"))));
        verify(bus, never()).broadcast(anyString(), argThat(frame -> "ACK".equals(frame.get("type"))), any());
        // The frame the service built is not the one that went out, and it is not mutated in place.
        assertThat(content).doesNotContainKey("onlineCount");
        // Exactly one of each, and nothing else: an extra copy to the sender would be a self-echo.
        verify(bus, times(1)).broadcast(anyString(), any(), any());
        verify(bus, times(1)).deliverLocal(anyString(), anyString(), any());
        verifyNoMoreInteractions(bus);
    }

    /** The ordering the sender's own liveness depends on: unicast first, then presence, then broadcast. */
    @Test
    void theAckIsUnicastBeforeTheBroadcastSoAPublishFailureCannotStallTheSender() {
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bus);
        Map<String, Object> content = new LinkedHashMap<>(Map.of("type", "STEPS", "version", 1));
        Map<String, Object> ack = new LinkedHashMap<>(Map.of("type", "ACK", "clientId", "c-1", "version", 1));

        new WebSocketObserver(bus, "7").update(new ContentAppliedEvent(7L, "session-a", content, ack));

        order.verify(bus).deliverLocal(eq("7"), eq("session-a"), any());
        order.verify(bus).onlineCount("7");
        order.verify(bus).broadcast(eq("7"), any(), eq("session-a"));
        order.verifyNoMoreInteractions();
    }

    /** An observer ignores events that belong to another document. */
    @Test
    void anObserverIgnoresEventsForOtherDocuments() {
        new WebSocketObserver(bus, "7").update(new ContentAppliedEvent(8L, "session-a",
                new LinkedHashMap<>(Map.of("type", "STEPS", "version", 1))));

        verifyNoInteractions(bus);
    }

    /**
     * The exclusion actually happens at the session map, not only in the observer: a registered session
     * whose id equals {@code excludeSessionId} receives nothing, another session **of the same account**
     * still does, and a client whose socket throws mid-delivery does not propagate out of the fan-out.
     */
    @Test
    void theSessionMapSkipsTheOriginAndSurvivesAFailingClient() throws Exception {
        WebSocketSessionManager sessions = new WebSocketSessionManager(new ObjectMapper());
        WebSocketSession origin = session("session-a");
        WebSocketSession sameUserSecondTab = session("session-d");
        WebSocketSession broken = session("session-b");
        WebSocketSession healthy = session("session-c");
        org.mockito.Mockito.doThrow(new IOException("client went away")).when(broken).sendMessage(any());
        sessions.register("7", origin, 1L);
        sessions.register("7", sameUserSecondTab, 1L);
        sessions.register("7", broken, 2L);
        sessions.register("7", healthy, 3L);

        Map<String, Object> frame = new LinkedHashMap<>(Map.of("type", "STEPS", "version", 1));
        assertThatCode(() -> sessions.deliverToDocument("7", frame, "session-a")).doesNotThrowAnyException();

        verify(origin, never()).sendMessage(any(TextMessage.class));
        verify(sameUserSecondTab).sendMessage(any(TextMessage.class));
        verify(broken).sendMessage(any(TextMessage.class));
        verify(healthy).sendMessage(any(TextMessage.class));
    }

    /** The subject keeps one observer per document, prefers it to the fallback, and counts sessions. */
    @Test
    void oneAttachedObserverPerDocumentAndItWinsOverTheFallback() {
        CollabBus subjectBus = mock(CollabBus.class);
        DocumentSubjectImpl subject = new DocumentSubjectImpl(subjectBus);
        DocumentObserver attached = mock(DocumentObserver.class);
        when(attached.getDocumentId()).thenReturn("9");

        subject.attach(attached);
        subject.attach(attached);
        subject.notifyAllObservers(new ContentAppliedEvent(9L, "s",
                new LinkedHashMap<>(Map.of("type", "STEPS", "version", 1))));
        verify(attached, times(1)).update(any());

        subject.detach("9");
        subject.notifyAllObservers(new ContentAppliedEvent(9L, "s",
                new LinkedHashMap<>(Map.of("type", "STEPS", "version", 2))));
        verify(attached, times(2)).update(any());

        subject.detach("9");
        subject.notifyAllObservers(new ContentAppliedEvent(9L, "s",
                new LinkedHashMap<>(Map.of("type", "STEPS", "version", 3))));
        verify(attached, times(2)).update(any());
        // With nothing attached the event still goes out, through the fallback observer on the real bus.
        verify(subjectBus).onlineCount("9");
        verify(subjectBus, times(1)).broadcast(eq("9"), any(), any());
        verifyNoMoreInteractions(subjectBus);
    }

    /** Nothing may reach a client for a write that did not commit. */
    @Test
    void rolledBackWriteFansOutNothing() {
        User owner = newUser();
        long docId = documentService.createDocument("rollback", owner.getId()).getId();
        TransactionTemplate template = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> template.execute(status -> {
            documentService.appendStepBatch(docId, owner.getId(), 0, "c-1", steps("q"), 1, "session-a");
            throw new IllegalStateException("failure after the event was published");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("failure after");

        verifyNoInteractions(bus);
        assertThat(documentService.getDocumentState(docId).getVersion()).isZero();
        // A log row that outlives its version would be replayed to clients as a step that never happened.
        assertThat(documentService.getOperationsAfter(docId, owner.getId(), 0)).isEmpty();
    }

    /** The positive control above: a committed batch is broadcast once and acked once. */
    @Test
    void committedWriteFansOutExactlyOnce() {
        User owner = newUser();
        long docId = documentService.createDocument("commit", owner.getId()).getId();

        int seq = documentService.appendStepBatch(docId, owner.getId(), 0, "c-1", steps("q"), 1, "session-a");

        assertThat(seq).isEqualTo(1);
        verify(bus, times(1)).broadcast(eq(String.valueOf(docId)),
                argThat(frame -> "STEPS".equals(frame.get("type"))), eq("session-a"));
        verify(bus, times(1)).deliverLocal(eq(String.valueOf(docId)), eq("session-a"),
                argThat(frame -> "ACK".equals(frame.get("type"))));
        verify(bus).onlineCount(String.valueOf(docId));
        verifyNoMoreInteractions(bus);
    }

    /**
     * The sequence a client replays is strictly increasing and gapless, the {@code after} bound is
     * exclusive, and each row still carries the steps its author submitted — otherwise two clients
     * starting from the same checkpoint could not converge on the same document.
     */
    @Test
    void replayedOperationsAreContiguousAndCarryTheSubmittedSteps() throws Exception {
        User owner = newUser();
        Document doc = documentService.createDocument("replay", owner.getId());
        int version = doc.getVersion();
        for (int i = 1; i <= 4; i++) {
            version = documentService.appendStepBatch(doc.getId(), owner.getId(), version,
                    "c-" + i, steps("m" + i), 1, "session-" + i);
        }

        List<OperationLogDTO> replay = documentService.getOperationsAfter(doc.getId(), owner.getId(), 0);
        assertThat(replay).extracting(OperationLogDTO::getVersion).containsExactly(1, 2, 3, 4);
        for (int i = 0; i < replay.size(); i++) {
            JsonNode row = params(replay.get(i).getCommandParams());
            assertThat(row.path("clientId").asText()).isEqualTo("c-" + (i + 1));
            assertThat(row.path("steps").get(0).path("slice").path("content").get(0).path("text").asText())
                    .isEqualTo("m" + (i + 1));
        }
        assertThat(documentService.getOperationsAfter(doc.getId(), owner.getId(), 2))
                .extracting(OperationLogDTO::getVersion).containsExactly(3, 4);
    }
}

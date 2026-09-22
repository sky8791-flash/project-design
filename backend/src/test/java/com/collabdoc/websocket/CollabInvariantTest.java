package com.collabdoc.websocket;

import com.collabdoc.dto.ContentAppliedEvent;
import com.collabdoc.dto.OperationLogDTO;
import com.collabdoc.entity.Document;
import com.collabdoc.entity.User;
import com.collabdoc.service.DocumentService;
import com.collabdoc.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.collabdoc.pattern.observer.WebSocketObserver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The invariants the collaboration design is supposed to hold by, and that everything else assumes:
 * a client never receives its own content back, a rolled back write reaches nobody, and the log a client
 * replays is a gapless total order carrying exactly what its authors submitted.
 *
 * <p>The bus is mocked so delivery is observable as method calls; the sequencer and the transaction
 * boundaries are the real ones.</p>
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

    /** I4: the sender's own batch is never broadcast back to it, and its ACK goes to no one else. */
    @Test
    void broadcastExcludesTheOriginSessionWhileTheAckReachesOnlyTheOrigin() {
        WebSocketObserver observer = new WebSocketObserver(bus, "7");
        Map<String, Object> content = Map.of("type", "STEPS", "version", 3);
        Map<String, Object> ack = Map.of("type", "ACK", "clientId", "c-1", "version", 3);

        observer.update(new ContentAppliedEvent(7L, "session-a", content, ack));

        verify(bus).broadcast(eq("7"),
                argThat(frame -> "STEPS".equals(frame.get("type"))
                        // Presence is stamped at send time, so what goes out is a copy of the frame.
                        && Integer.valueOf(3).equals(frame.get("version"))
                        && frame.containsKey("onlineCount")),
                eq("session-a"));
        verify(bus).deliverLocal(eq("7"), eq("session-a"), eq(ack));
        // The content frame must never be unicasted, and the ACK must never be broadcast.
        verify(bus, never()).deliverLocal(anyString(), anyString(), eq(content));
        verify(bus, never()).broadcast(anyString(), eq(ack), any());
    }

    /** One observer per document: another document's event must not touch this one's bus calls. */
    @Test
    void anObserverIgnoresEventsForOtherDocuments() {
        new WebSocketObserver(bus, "7").update(new ContentAppliedEvent(8L, "session-a",
                Map.of("type", "STEPS", "version", 1)));

        verifyNoInteractions(bus);
    }

    /** I6: nothing may reach a client for a write that did not commit. */
    @Test
    void rolledBackWriteFansOutNothing() {
        User owner = newUser();
        long docId = documentService.createDocument("rollback", owner.getId()).getId();
        TransactionTemplate template = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> template.execute(status -> {
            documentService.appendStepBatch(docId, owner.getId(), 0, "c-1", steps("z"), 1, "session-a");
            throw new IllegalStateException("failure after the event was published");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("failure after");

        verifyNoInteractions(bus);
        assertThat(documentService.getDocumentState(docId).getVersion()).isZero();
    }

    /** The positive control for the test above: a committed batch is broadcast once and acked once. */
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
    }

    /**
     * I2's server-side precondition: the sequence a client replays is gapless and strictly increasing, and
     * every row still carries the steps its author submitted — otherwise two clients starting from the same
     * checkpoint could not converge on the same document.
     */
    @Test
    void replayedOperationsAreContiguousAndCarryTheSubmittedSteps() {
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
            // H2 hands a JSON column back quoted, so the marker is matched rather than parsed.
            assertThat(replay.get(i).getCommandParams()).contains("m" + (i + 1));
        }
    }
}

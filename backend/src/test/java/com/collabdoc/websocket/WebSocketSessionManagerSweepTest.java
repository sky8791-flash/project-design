package com.collabdoc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The producer half of membership discovery. Which sessions get reported, and with which open flag, is decided
 * here rather than in the bus — so a bus test cannot see it: handing the bus a filtered list while still
 * passing the open flag would leave the ghost undiscovered and every test green.
 */
class WebSocketSessionManagerSweepTest {

    @Test
    void reportsClosedSessionsAndStopsHoldingThem() {
        WebSocketSessionManager registry = new WebSocketSessionManager(new ObjectMapper());
        registry.register("7", session("live", true), 1L);
        registry.register("7", session("ghost", false), 2L);

        List<String> first = visit(registry);
        assertThat(first).containsExactlyInAnyOrder("live:true", "ghost:false");

        // Nothing else ever removes a session whose afterConnectionClosed did not run, so holding it would make
        // every following tick rediscover it and repeat a delete that has already landed. The live one stays.
        assertThat(visit(registry)).containsExactly("live:true");
    }

    @Test
    void dropsTheDocumentEntryOnceItsLastSessionIsGone() {
        WebSocketSessionManager registry = new WebSocketSessionManager(new ObjectMapper());
        registry.register("7", session("only", false), 1L);

        assertThat(visit(registry)).containsExactly("only:false");
        // An empty document map left behind would keep the ghost's document in the membership pass forever.
        assertThat(visit(registry)).isEmpty();
        assertThat(registry.localOnlineCount("7")).isZero();
    }

    private static List<String> visit(WebSocketSessionManager registry) {
        List<String> seen = new ArrayList<>();
        registry.forEachLiveSession((documentId, sessionId, open) -> seen.add(sessionId + ":" + open));
        return seen;
    }

    private static WebSocketSession session(String id, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(open);
        return session;
    }
}

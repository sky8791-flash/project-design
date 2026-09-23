package com.collabdoc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The receive half of the redis bus: an envelope off the channel has to reach the sessions it names, and
 * anything else has to disappear without touching them.
 */
class RedisCollabBusFrameTest {

    private final LocalDelivery delivery = mock(LocalDelivery.class);
    private final RedisCollabBus bus =
            new RedisCollabBus(mock(StringRedisTemplate.class), new ObjectMapper(), delivery);

    @Test
    void aDocumentFrameGoesToThatDocumentWithItsOriginExcluded() {
        receive("""
                {"documentId":"7","excludeSessionId":"s2","frame":{"type":"STEPS","version":3}}""");
        verify(delivery).deliverToDocument("7", Map.of("type", "STEPS", "version", 3), "s2");
    }

    @Test
    void aUserFrameGoesToThatUser() {
        receive("""
                {"userId":5,"frame":{"type":"NOTIFICATION"}}""");
        verify(delivery).deliverToUser(5L, Map.of("type", "NOTIFICATION"));
    }

    /**
     * A frame carrying neither would otherwise be looked up in a map keyed on null, which on a
     * {@code ConcurrentHashMap} is an exception on the listener thread.
     */
    @Test
    void anEnvelopeNamingNobodyIsDropped() {
        receive("""
                {"frame":{"type":"NOTIFICATION"}}""");
        verifyNoInteractions(delivery);
    }

    @Test
    void unparsableJsonReachesNoSession() {
        receive("this is not an envelope");
        verifyNoInteractions(delivery);
    }

    private void receive(String json) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        bus.onFrame(message, null);
    }
}

package com.collabdoc.websocket;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The one thing no test has ever done: start the application in {@code app.collab.bus=redis}.
 *
 * <p>A Redis server is not needed to ask the interesting questions. Lettuce connects lazily, so the context
 * has to come up regardless, and everything the bus does afterwards has to degrade rather than propagate —
 * that is the contract in {@link CollabBus}, and its callers are post-commit fan-out and socket lifecycle,
 * where a thrown exception costs a 5xx for a write that already committed or closes a healthy connection.
 * Pointing the client at a port nothing listens on makes the failure immediate and deterministic, so this
 * covers the mode the memory-bus tests never touch: which implementation gets wired, and whether an outage
 * can be heard from outside.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.collab.bus=redis",
    "spring.data.redis.host=127.0.0.1",
    "spring.data.redis.port=1",
    "spring.data.redis.connect-timeout=200ms",
    "spring.data.redis.timeout=200ms"
})
class RedisBusModeTest {

    @Autowired
    CollabBus bus;

    @Test
    void theRedisBusIsWhatGetsWired() {
        assertThat(bus).isInstanceOf(RedisCollabBus.class);
    }

    @Test
    void everyCallDegradesWhileRedisIsUnreachable() {
        assertThatCode(() -> bus.sessionJoined("1", "session-a")).doesNotThrowAnyException();
        assertThatCode(() -> bus.broadcast("1", Map.of("type", "STEPS", "version", 1), "session-a"))
                .doesNotThrowAnyException();
        assertThatCode(() -> bus.toUser(1L, Map.of("type", "NOTIFICATION"))).doesNotThrowAnyException();
        assertThatCode(() -> bus.sessionLeft("1", "session-a")).doesNotThrowAnyException();
    }

    /**
     * The read path runs while the document row lock is held, so an outage must answer from this node rather
     * than fail the request.
     */
    @Test
    void onlineCountFallsBackToLocalNumbers() {
        assertThat(bus.onlineCount("1")).isZero();
    }

    @Test
    void theMembershipHeartbeatSurvivesAnOutage() {
        assertThatCode(() -> ((RedisCollabBus) bus).membershipHeartbeat()).doesNotThrowAnyException();
    }

    /**
     * The failing subscription is the point of the whole degraded-start design, so its wording is asserted.
     *
     * <p>The container is built by hand rather than being a bean, which means nothing calls
     * {@code afterPropertiesSet()} for us — skip it and every attempt fails with "Subscriber not created",
     * with Redis healthy, forever and quietly. The message distinguishes the two failures. Waiting for a
     * second one is the only way to see the retry loop at all, since no test has a Redis to succeed against.
     * </p>
     */
    @Test
    void theListenerRetriesAndBlamesTheConnectionNotItsOwnSetup() throws InterruptedException {
        Logger log = (Logger) LoggerFactory.getLogger(RedisCollabBus.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        log.addAppender(events);
        try {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline && attempts(events).size() < 2) {
                Thread.sleep(200);
            }
            List<String> attempts = attempts(events);
            assertThat(attempts).as("the retry loop ran more than once").hasSizeGreaterThanOrEqualTo(2);
            assertThat(attempts.get(0)).contains("Unable to connect to Redis");
            assertThat(attempts.get(0)).doesNotContain("Subscriber not created");
        } finally {
            log.detachAppender(events);
        }
    }

    private static List<String> attempts(ListAppender<ILoggingEvent> events) {
        return events.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("could not subscribe"))
                .toList();
    }
}

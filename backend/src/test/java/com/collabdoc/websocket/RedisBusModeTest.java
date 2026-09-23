package com.collabdoc.websocket;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
     * The failing subscription is the point of the whole degraded-start design, so its wording is asserted:
     * "Unable to connect to Redis" means the attempt reached the network, while "Subscriber not created" would
     * mean the hand-built container skipped {@code afterPropertiesSet()} and would keep failing with Redis
     * perfectly healthy. Checked by mutation — deleting that call turns this red on exactly that message, which
     * is the only reason to trust the comment beside it.
     *
     * <p>Only the first warning is waited for: the cadence doubles from five seconds, so a second one costs
     * fifteen and proves nothing this test is about. {@code CollabFrameListenerTest} covers the loop, the stop
     * and the superseded attempt at a millisecond cadence that can actually be asserted.</p>
     */
    private static final Recorded LOG = new Recorded();

    @BeforeAll
    static void watchTheBusLogger() {
        attach();
    }

    @AfterAll
    static void stopWatchingTheBusLogger() {
        ((Logger) LoggerFactory.getLogger(RedisCollabBus.class)).detachAppender(LOG);
    }

    /**
     * Attaches the recorder to the bus logger unless something already has it.
     *
     * <p>Called from the poll loop as well as from {@code @BeforeAll}, because Boot initialises Logback around
     * the context load and that reset drops an appender attached before it. Attached once at class start, this
     * recorded nothing on its own and everything on its own when the class ran first — the wording under test is
     * still worth asserting, so the attachment has to survive rather than the assertion have to move.</p>
     */
    private static void attach() {
        Logger logger = (Logger) LoggerFactory.getLogger(RedisCollabBus.class);
        if (!LOG.isStarted()) LOG.start();
        Iterator<ch.qos.logback.core.Appender<ILoggingEvent>> attached = logger.iteratorForAppenders();
        while (attached.hasNext()) {
            if (attached.next() == LOG) return;
        }
        logger.addAppender(LOG);
    }

    @Test
    void theListenerBlamesTheConnectionRatherThanItsOwnSetup() throws InterruptedException {
        List<String> attempts = List.of();
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline && attempts.isEmpty()) {
            attach();
            Thread.sleep(500);
            attempts = LOG.matching("could not subscribe");
        }
        assertThat(attempts)
                .as("the listener reported why it could not subscribe; the listener logged %s", LOG.tail(5))
                .isNotEmpty();
        assertThat(attempts.get(0)).contains("Unable to connect to Redis");
        assertThat(attempts.get(0)).doesNotContain("Subscriber not created");
    }

    /**
     * Logback's own {@code ListAppender} appends to a plain {@code ArrayList} without holding a lock, and this
     * class listens for the lifetime of a context whose retry thread keeps writing while the test polls, so the
     * recording list has to be thread-safe.
     */
    private static final class Recorded extends AppenderBase<ILoggingEvent> {

        private final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            messages.add(event.getFormattedMessage());
        }

        List<String> matching(String fragment) {
            return messages.stream().filter(message -> message.contains(fragment)).toList();
        }

        /** What the listener did say, for a failure message that can be diagnosed without rerunning. */
        List<String> tail(int count) {
            return messages.subList(Math.max(0, messages.size() - count), messages.size());
        }
    }
}

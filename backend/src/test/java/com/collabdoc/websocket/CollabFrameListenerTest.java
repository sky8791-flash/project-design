package com.collabdoc.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The retry loop and the rules about who may run it.
 *
 * <p>Every wait here is a predicate on state the loop actually produces rather than a fixed sleep, because the
 * first attempt costs a few hundred milliseconds on a cold JVM — Spring Data Redis, Lettuce and Mockito all
 * load during it — and only a few once something has warmed those classes up. An assertion on how many
 * attempts fit into 150 ms therefore passes or fails depending on which tests ran before it, which is not
 * hypothetical: the first version of this class was green inside {@code mvn test} and red on its own for
 * exactly that reason.</p>
 *
 * <p>What is not covered: the CAS that adopts the one container per lifecycle, and the release that follows an
 * adoption its {@code stop()} missed. Both need an attempt that <em>succeeds</em>, and no test here has a Redis
 * to succeed against — that half is reasoned, and is what the two-instance verification is still blocked on.</p>
 */
class CollabFrameListenerTest {

    @Test
    void itRetriesUntilStopEndsTheLoop() {
        RecordingFactory redis = new RecordingFactory();
        RedisCollabBus.CollabFrameListener listener = new RedisCollabBus.CollabFrameListener(
                redis.proxy(), (message, pattern) -> {
                }, Duration.ofMillis(1), Duration.ofMillis(2));

        listener.start();
        redis.awaitCalls(5, "a one millisecond cadence to retry");
        Thread attempt = redis.caller(0);

        listener.stop();
        await(() -> !attempt.isAlive(),
                "stop() to end the retry thread instead of leaving it outliving the context");

        int settled = redis.calls();
        // The cadence is a millisecond, so a loop still running would add hundreds in this window. The thread
        // being dead is the claim; this is the cheap direct check of the same thing.
        sleep(100);
        assertThat(redis.calls()).as("nothing retries once the listener is stopped").isEqualTo(settled);
        assertThat(listener.isRunning()).as("the lifecycle reports itself stopped").isFalse();
    }

    /**
     * {@code stop()} interrupts, and this is the case that needs it: with the retry gap capped at a minute, a
     * shutdown that only waited for {@code running} would sit out the rest of a sleep first. Delete the
     * interrupt from {@code stop()} and the loop still ends — just up to half a minute later, which is the
     * difference between a fast context close and a hung one.
     */
    @Test
    void stopDoesNotWaitForAPendingRetry() {
        RecordingFactory redis = new RecordingFactory();
        RedisCollabBus.CollabFrameListener listener = new RedisCollabBus.CollabFrameListener(
                redis.proxy(), (message, pattern) -> {
                }, Duration.ofSeconds(30), Duration.ofSeconds(30));

        listener.start();
        redis.awaitCalls(1, "the first attempt");
        Thread attempt = redis.caller(0);
        // The connection fails at once in this mode, so the only place a millisecond-thin thread can park is
        // the retry wait — which is exactly the state the interrupt has to break.
        await(() -> attempt.getState() == Thread.State.TIMED_WAITING,
                "the attempt to be asleep in its thirty second wait");

        listener.stop();
        await(() -> !attempt.isAlive(), "stop() to cut short the pending retry rather than wait it out");
    }

    /**
     * The generation guard, and the one case only it can answer.
     *
     * <p>{@code stop()} interrupts the attempt thread, and an interrupt ends a sleep but not a blocking
     * connect — so the mocked connection ignores it, the way a socket read does. The superseded thread then
     * wakes to find {@code running} true again, set by its replacement, and nothing but the generation says
     * it is no longer the attempt that owns this lifecycle. Delete the guard and this thread keeps retrying
     * beside the live one; the survivor staying alive is what stops that from passing for a clean shutdown.</p>
     */
    @Test
    void aSupersededAttemptRetiresEvenThoughTheInterruptDidNotStopIt() {
        RecordingFactory redis = new RecordingFactory();
        RedisCollabBus.CollabFrameListener listener = new RedisCollabBus.CollabFrameListener(
                redis.proxy(), (message, pattern) -> {
                }, Duration.ofMillis(1), Duration.ofMillis(2));

        redis.holdCalls();
        Thread superseded;
        Thread current;
        try {
            listener.start();
            superseded = redis.awaitCaller(0);
            // Deliberately not joined: the point is that this thread survives the stop.
            listener.stop();
            listener.start();
            current = redis.awaitCaller(1);
            assertThat(current).as("the second lifecycle runs its own attempt thread").isNotEqualTo(superseded);

            redis.release();
            await(() -> redis.callsFrom(current) >= 3, "the owning attempt to settle into its retry cadence");

            await(() -> !superseded.isAlive(), "the superseded attempt to retire");
            assertThat(current.isAlive())
                    .as("retiring the superseded attempt must not stop the live one")
                    .isTrue();
        } finally {
            // Nothing may be left parked on a failing assertion: parked threads would retry at full speed for
            // the rest of the run.
            redis.release();
            listener.stop();
        }

        await(() -> !current.isAlive(), "the owning attempt to stop as well");
        assertThatCode(listener::stop).doesNotThrowAnyException();
    }

    /**
     * A connection factory that fails the way an unreachable Redis fails, and records which thread asked.
     *
     * <p>Recorded per call rather than counted from Mockito's invocation log because the container talks to the
     * factory more than once per attempt, and {@code destroy} may talk to it too — what the loop's own thread
     * did is the only thing either test needs, and it is the one thing that stays stable across versions.</p>
     */
    private static final class RecordingFactory {

        private final List<Thread> callers = new CopyOnWriteArrayList<>();
        private final AtomicBoolean parked = new AtomicBoolean();
        private final RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        RecordingFactory() {
            when(connectionFactory.getConnection()).thenAnswer(call -> {
                callers.add(Thread.currentThread());
                while (parked.get()) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException ignored) {
                        // Swallowed without restoring the flag, because that is what a blocking connect does,
                        // and an attempt thread that died on the interrupt would prove nothing about the guard.
                    }
                }
                throw new RedisConnectionFailureException("Unable to connect to Redis");
            });
        }

        RedisConnectionFactory proxy() {
            return connectionFactory;
        }

        /** Makes later calls park instead of failing, so a thread can be interrupted mid-connect. */
        void holdCalls() {
            parked.set(true);
        }

        void release() {
            parked.set(false);
        }

        int calls() {
            return callers.size();
        }

        int callsFrom(Thread thread) {
            return (int) callers.stream().filter(caller -> caller == thread).count();
        }

        Thread caller(int index) {
            return callers.get(index);
        }

        /** @return the thread that made the {@code index}-th call, once there has been one. */
        Thread awaitCaller(int index) {
            awaitCalls(index + 1, "the " + (index + 1) + "-th connection attempt");
            return callers.get(index);
        }

        void awaitCalls(int atLeast, String what) {
            await(() -> callers.size() >= atLeast, what);
        }
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
            sleep(5);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting");
        }
    }
}

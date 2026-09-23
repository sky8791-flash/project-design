package com.collabdoc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.invocation.Invocation;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * The membership half of the redis bus: the one place where presence can go permanently wrong.
 *
 * <p>A ghost member in {@code collab:doc:{id}} carries this node's own live lease, so no other node ever
 * prunes it and {@code onlineCount} stays too high on that document until the process restarts. Only the
 * heartbeat can fix it, which makes the interaction between its two halves — the {@code HSETALL} that
 * re-asserts everything this node holds and the {@code HDEL} that honours the removals it owes — the
 * invariant worth pinning. Redis is mocked at the template level because the code under test <em>is</em>
 * the command sequence: which calls, in which order, and with which fields.</p>
 *
 * <p>Assertions therefore count <em>attempts</em>, never results: a mocked Redis has no state, so "the ghost
 * is gone from the hash" is not observable here. What is observable is the contract the real code makes to
 * Redis, and the retry path is checked by letting a call fail, then letting the next tick succeed.</p>
 */
class RedisCollabBusMembershipTest {

    /** Sessions this node holds, keyed by document. */
    private final Map<String, List<String>> live = new TreeMap<>();
    private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisCollabBus bus;

    RedisCollabBusMembershipTest() {
        doReturn(hashOps).when(redis).opsForHash();
        doReturn(valueOps).when(redis).opsForValue();
        LocalDelivery delivery = mock(LocalDelivery.class);
        doAnswer(call -> {
            BiConsumer<String, String> visitor = call.getArgument(0);
            live.forEach((documentId, sessions) -> sessions.forEach(s -> visitor.accept(documentId, s)));
            return null;
        }).when(delivery).forEachLiveSession(any());
        bus = new RedisCollabBus(redis, new ObjectMapper(), delivery);
    }

    @Test
    void aSessionClosedAfterTheSweepSnapshottedItIsNotReAssertedButStillDropped() {
        live.put("7", new ArrayList<>(List.of("s1", "s2")));
        bus.sessionLeft("7", "s2");

        // The snapshot for this tick still held s2. Re-asserting it would put a field naming a live node back
        // into the hash, where no other node may prune it, for as long as this node's own HDEL kept failing.
        clearInvocations(hashOps);
        bus.membershipHeartbeat();

        assertThat(reasserted().get("collab:doc:7").keySet()).containsExactly("s1");
        assertThat(deletedFields("collab:doc:7")).containsExactly("s2");
    }

    @Test
    void aCloseOwesOneRemovalPerDocumentNotPerSession() {
        for (int i = 0; i < 40; i++) {
            bus.sessionLeft("7", "s" + i);
        }
        bus.sessionLeft("8", "other");

        clearInvocations(hashOps);
        bus.membershipHeartbeat();
        // 40 fields in one call: the previous per-member budget cleared 32 a tick and left the rest behind.
        assertThat(deleteCalls("collab:doc:7")).isEqualTo(1);
        assertThat(deleteCalls("collab:doc:8")).isEqualTo(1);
        assertThat(deletedFields("collab:doc:7")).hasSize(40);

        // A cleared intent must stay cleared: retrying it every tick is what turned a poison key into a
        // permanent block on the documents that came after it in iteration order.
        clearInvocations(hashOps);
        bus.membershipHeartbeat();
        assertThat(deleteCalls("collab:doc:7")).isZero();
    }

    @Test
    void aRemovalThatCouldNotBeSentIsRetriedUntilItLands() {
        when(hashOps.delete(any(), any())).thenThrow(new RuntimeException("Connection refused"));
        bus.sessionLeft("7", "s1");
        clearInvocations(hashOps);

        // Counts below are cumulative since the clear above: one attempt per tick that owed the removal.
        bus.membershipHeartbeat();
        assertThat(deleteCalls("collab:doc:7"))
                .as("a failed HDEL must leave the intent, not clear it")
                .isEqualTo(1);

        doReturn(0L).when(hashOps).delete(any(), any());
        bus.membershipHeartbeat();
        assertThat(deleteCalls("collab:doc:7"))
                .as("the heartbeat is the only thing that can clear the ghost, so it retries")
                .isEqualTo(2);

        bus.membershipHeartbeat();
        assertThat(deleteCalls("collab:doc:7"))
                .as("a removal that landed is not owed again")
                .isEqualTo(2);
    }

    @Test
    void leaseIsRenewedFirstAndOnePoisonedDocumentCostsNeither() {
        live.put("7", new ArrayList<>(List.of("s1")));
        live.put("8", new ArrayList<>(List.of("s2")));
        doAnswer(call -> {
            throw new RuntimeException("WRONGTYPE");
        }).when(hashOps).putAll(eq("collab:doc:7"), any());

        assertThatCode(() -> bus.membershipHeartbeat()).doesNotThrowAnyException();

        InOrder order = inOrder(valueOps, hashOps);
        order.verify(valueOps).set(anyString(), anyString(), any(Duration.class));
        order.verify(hashOps).putAll(eq("collab:doc:7"), any());
        // The loop continued past the failure: one bad key must not cost this node presence everywhere else.
        assertThat(reasserted().get("collab:doc:8").keySet()).containsExactly("s2");
    }

    @Test
    void aTickStopsAtTheFailureCapAndTheNextOneResumesPastIt() {
        for (int i = 0; i < 10; i++) {
            live.put("doc" + i, new ArrayList<>(List.of("s")));
        }
        doAnswer(call -> {
            throw new RuntimeException("Connection refused");
        }).when(hashOps).putAll(any(), any());

        bus.membershipHeartbeat();
        // Failures, not attempts, are budgeted: with a cap that counted attempts the first 8 would be the
        // only documents ever visited while they kept failing.
        assertThat(visitedKeys()).hasSize(8);

        clearInvocations(hashOps);
        bus.membershipHeartbeat();
        assertThat(visitedKeys()).startsWith("collab:doc:doc8").hasSize(8);
    }

    /** The documents one tick visited, in call order. */
    private List<String> visitedKeys() {
        List<String> keys = new ArrayList<>();
        for (Invocation call : mockingDetails(hashOps).getInvocations()) {
            if (!"putAll".equals(call.getMethod().getName())) continue;
            keys.add((String) flatten(call.getArguments()).get(0));
        }
        return keys;
    }

    /** Fields the bus tried to {@code HDEL} from one document's key. */
    private Set<String> deletedFields(String key) {
        Set<String> fields = new TreeSet<>();
        for (List<Object> args : deleteArguments(key)) {
            fields.addAll(args.subList(1, args.size()).stream().map(String.class::cast).toList());
        }
        return fields;
    }

    /** How many {@code HDEL} calls targeted one document's key. */
    private long deleteCalls(String key) {
        return deleteArguments(key).size();
    }

    /** Each {@code HDEL} call on {@code key} as a flattened argument list; tolerates both varargs shapes. */
    private List<List<Object>> deleteArguments(String key) {
        List<List<Object>> calls = new ArrayList<>();
        for (Invocation call : mockingDetails(hashOps).getInvocations()) {
            if (!"delete".equals(call.getMethod().getName())) continue;
            List<Object> args = flatten(call.getArguments());
            if (key.equals(args.get(0))) calls.add(args);
        }
        return calls;
    }

    /** {@code HSETALL} calls keyed by the hash they wrote to. */
    private Map<String, Map<Object, Object>> reasserted() {
        Map<String, Map<Object, Object>> byKey = new TreeMap<>();
        for (Invocation call : mockingDetails(hashOps).getInvocations()) {
            if (!"putAll".equals(call.getMethod().getName())) continue;
            List<Object> args = flatten(call.getArguments());
            byKey.put((String) args.get(0), (Map<Object, Object>) args.get(1));
        }
        return byKey;
    }

    private static List<Object> flatten(Object[] args) {
        List<Object> flat = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof Object[] group) {
                flat.addAll(Arrays.asList(group));
            } else {
                flat.add(arg);
            }
        }
        return flat;
    }
}

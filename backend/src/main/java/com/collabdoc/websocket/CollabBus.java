package com.collabdoc.websocket;

import java.util.Map;

/**
 * Membership and frame fan-out across the processes that hold the sockets.
 *
 * <p>A single instance can deliver straight from its own session map. Two instances cannot: a client
 * attached to node A would never see the broadcast produced by a write that node B sequenced. The
 * sequencing itself needs nothing here — versions are minted under a database row lock — so this
 * abstraction covers only presence and delivery.</p>
 * <p>A bus never throws. Its callers are post-commit fan-out and socket lifecycle paths, where an
 * exception would cost a 5xx for a write that already committed, or close a healthy connection. Losing a
 * frame is recoverable — every frame carries the version, so a client that sees a gap refetches the
 * operations over HTTP — so each implementation degrades and logs instead.</p>
 */
public interface CollabBus {

    void sessionJoined(String documentId, String sessionId);

    void sessionLeft(String documentId, String sessionId);

    /**
     * Sessions across every node, so the number a client sees matches the document, not this JVM. The two
     * implementations answer from different registries on purpose: the memory bus reads the local session
     * map, the redis bus reads the shared membership hash.
     */
    int onlineCount(String documentId);

    /**
     * Delivered by every node to the sessions it holds, skipping {@code excludeSessionId}. Cross-node
     * exclusion works because the id travels inside the envelope and Spring issues session ids as UUIDs;
     * a generator that could reuse an id on two nodes would start suppressing the wrong client.
     */
    void broadcast(String documentId, Map<String, Object> frame, String excludeSessionId);

    void toUser(Long userId, Map<String, Object> frame);

    /**
     * Unicast to a session this process holds. Never published: an ACK belongs to the node that owns the
     * socket, and routing it through a bus would only bounce it back to the same node.
     *
     * <p>That only holds while the caller is the node that received the write. If a step batch is ever
     * appended from a REST controller or a scheduled job, its {@code originSessionId} belongs to another
     * JVM and the acknowledgement would disappear into a map miss.</p>
     */
    void deliverLocal(String documentId, String sessionId, Map<String, Object> frame);
}

import java.util.HashMap;
import java.util.Map;

/** Single-server-thread history, retained until process exit (no early eviction). */
public final class ReplyHistory {
    private record Key(long clientId, long requestId) { }
    private final Map<Key, byte[]> replies = new HashMap<>();

    public byte[] find(long clientId, long requestId) {
        byte[] reply = replies.get(new Key(clientId, requestId));
        return reply == null ? null : reply.clone();
    }

    public void remember(long clientId, long requestId, byte[] reply) {
        if (reply == null) throw new IllegalArgumentException("Null reply");
        replies.putIfAbsent(new Key(clientId, requestId), reply.clone());
    }
}

package fun.fengwk.kkstudio.harness.runtime.processor;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ThreadProcessor 的轻量级 claim 准入防重守卫。
 *
 * <p>ThreadProcessor 无长期执行体（无 execution），仅需在单 action 执行期间防止同一 threadId 的并发或重复投递：同一 token 的重复 /
 * 并发投递直接拒绝；不同 token 在旧 action 尚未结束时同样拒绝（无可 supersede 的本地 execution，LOST 后由 Work 重新可投递），绝不 supersede
 * 或取消任何对象。迟到 release 只删除仍由该 token 持有的槽位。
 */
final class ThreadAdmissionGuard {
  private final ConcurrentHashMap<UUID, String> activeClaims = new ConcurrentHashMap<>();

  boolean tryAdmit(UUID threadId, String token) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(token, "token");
    return activeClaims.putIfAbsent(threadId, token) == null;
  }

  void release(UUID threadId, String token) {
    if (threadId != null && token != null) {
      activeClaims.remove(threadId, token);
    }
  }
}

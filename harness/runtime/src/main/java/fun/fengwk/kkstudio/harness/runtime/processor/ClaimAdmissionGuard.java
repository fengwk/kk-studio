package fun.fengwk.kkstudio.harness.runtime.processor;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * per-invocation admission guard：覆盖 prepare 到 registry 插入窗口，供 Model / Tool processor 共享。
 *
 * <p>同一 claim（同 lease token）的重复 / 并发投递一律被拒绝（不 cancel、不 mutation）；不同 token（旧 lease 已过期）的并发 投递抢占
 * guard，使新 claim 可以安全 supersede 旧本地 execution。调用方必须在 try/finally 中对称调用 {@link #release}。
 */
final class ClaimAdmissionGuard {

  private final ConcurrentHashMap<UUID, String> tokens = new ConcurrentHashMap<>();

  /** 尝试为 invocation 抢占 admission；返回 false 表示同一 claim 已在处理中。 */
  boolean tryAdmit(UUID invocationId, String token) {
    AtomicBoolean admitted = new AtomicBoolean();
    tokens.compute(
        invocationId,
        (ignored, prior) -> {
          if (token.equals(prior)) {
            return prior;
          }
          admitted.set(true);
          return token;
        });
    return admitted.get();
  }

  /** 仅释放仍由指定 token 持有的准入槽，避免清除并发替换后的 claim。 */
  void release(UUID invocationId, String token) {
    tokens.remove(invocationId, token);
  }
}

package fun.fengwk.kkstudio.harness.builtin.processor;

import fun.fengwk.kkstudio.harness.builtin.port.store.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;

import java.time.Instant;

/**
 * 失败 attempt 审计快照的唯一构造点：执行中的失败取内存流快照，lease 恢复时取 durable checkpoint。
 *
 * <p>审计只记录发生过的事实（成员身份、请求时刻、已累计的安全 partial 与重试时刻），新 attempt 从空内容开始，绝不与旧 partial 拼接。
 */
final class ModelAttemptFailures {

  private ModelAttemptFailures() {}

  /** 运行线程提交终态时的失败审计：partial 来自尚未落库的流式内存快照。 */
  static ModelAttemptFailure fromStream(
      int attempt,
      ModelStreamPublisher.Snapshot snapshot,
      ModelInvocation model,
      ModelInvocationError error,
      Instant failedAt,
      Instant retryAt) {
    return of(
        attempt,
        snapshot.lastSafeSequence(),
        snapshot.text(),
        snapshot.thinking(),
        model,
        error,
        failedAt,
        retryAt);
  }

  /** lease 恢复时的失败审计：旧 attempt 的 partial 只取持久化的安全 checkpoint。 */
  static ModelAttemptFailure fromCheckpoint(
      ModelInvocation model,
      int attempt,
      ModelInvocationError error,
      Instant failedAt,
      Instant retryAt) {
    StreamCheckpoint checkpoint = model.streamCheckpoint();
    return of(
        attempt,
        checkpoint == null ? 0L : checkpoint.sequence(),
        checkpoint == null ? "" : checkpoint.text(),
        checkpoint == null ? "" : checkpoint.thinking(),
        model,
        error,
        failedAt,
        retryAt);
  }

  private static ModelAttemptFailure of(
      int attempt,
      long sequence,
      String text,
      String thinking,
      ModelInvocation model,
      ModelInvocationError error,
      Instant failedAt,
      Instant retryAt) {
    return new ModelAttemptFailure(
        attempt,
        sequence,
        text,
        thinking,
        model.actualSelection(),
        model.requestedAt(),
        error,
        failedAt,
        retryAt);
  }
}

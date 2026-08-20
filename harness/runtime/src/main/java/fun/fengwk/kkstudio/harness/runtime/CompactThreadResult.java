package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Objects;
import java.util.UUID;

/**
 * 手动压缩 plan 的 durable 提交结果。Resolver 接受时 modelInvocationId 非空；Resolver 业务拒绝时已关闭失败 turn，
 * modelInvocationId 为 null。
 */
public record CompactThreadResult(
    ThreadState thread, UUID turnStartEntryId, UUID modelInvocationId) {

  public CompactThreadResult {
    thread = Objects.requireNonNull(thread, "thread");
    turnStartEntryId = Objects.requireNonNull(turnStartEntryId, "turnStartEntryId");
  }
}

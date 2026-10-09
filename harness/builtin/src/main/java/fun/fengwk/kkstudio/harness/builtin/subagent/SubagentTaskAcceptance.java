package fun.fengwk.kkstudio.harness.builtin.subagent;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Subagent 委派的持久接受结果：子 Thread 已经 durable 存在，本次执行的 prompt 已经入队。
 *
 * <p>接受即返回，调用方（task Tool）立即回执 {@code thread_id + accepted}，不再等待结果。真正的完成结果由运行时在子线程结清后作为父线程的
 * 一条独立消息交付。
 *
 * @param childSessionId 子 Session UUID
 * @param childThreadId 子 Thread UUID（父调用后续用它继续该子执行）
 * @param replayed 该次接受是否命中了同 invocation 的既有持久记录（幂等重试）
 * @param replaced 该次接受是否在同一父/子对上 supersede 了一个既有未完成 join（busy follow-up：旧 pending wait
 *     被取代，只会有一份汇总结果）
 */
public record SubagentTaskAcceptance(
    UUID childSessionId, UUID childThreadId, boolean replayed, boolean replaced) {

  public SubagentTaskAcceptance {
    Objects.requireNonNull(childSessionId, "childSessionId");
    Objects.requireNonNull(childThreadId, "childThreadId");
  }
}

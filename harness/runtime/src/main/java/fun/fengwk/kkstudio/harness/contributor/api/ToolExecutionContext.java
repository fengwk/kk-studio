package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 提供给 Tool 执行的 durable 上下文环境。
 *
 * <p>除执行时刻与可选环境绑定外，全部事实都由 Harness 在 Tool admission 前从已锁定的 Thread 与该调用的 assistant 分支读出并冻结， 因此
 * Tool、Plugin 与平台业务不必、也不得为了这些事实再查询 Harness 存储：
 *
 * <ul>
 *   <li>{@code threadId} / {@code sessionId}：调用所属 Thread 与其自身会话；
 *   <li>{@code rootThreadId} / {@code rootSessionId}：该 Thread 不可变执行根及其所在会话；子代理运行在独立会话中时二者与上一组不同；
 *   <li>{@code assistantEntryId}：该调用所属 assistant 分支的入口 Entry。它由 Harness 从已冻结的 Tool 调用读出，因此 Tool
 *       可以按真实调用位置判断自己是否仍属于当前业务执行，不必、也不能自行推断；
 *   <li>{@code mediaCapabilities}：发起 Turn 在 TURN_START 中冻结的消费者媒体能力，是媒体可送达判定的唯一来源。
 * </ul>
 */
public record ToolExecutionContext(
    UUID invocationId,
    UUID threadId,
    UUID sessionId,
    UUID rootThreadId,
    UUID rootSessionId,
    UUID assistantEntryId,
    Instant executedAt,
    TurnMediaCapabilities mediaCapabilities,
    Optional<BoundEnvironment> environment) {

  public ToolExecutionContext {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(rootSessionId, "rootSessionId");
    Objects.requireNonNull(assistantEntryId, "assistantEntryId");
    Objects.requireNonNull(executedAt, "executedAt");
    Objects.requireNonNull(mediaCapabilities, "mediaCapabilities");
    Objects.requireNonNull(environment, "environment");
  }
}

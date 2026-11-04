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
 *   <li>{@code mediaCapabilities}：发起 Turn 在 TURN_START 中冻结的消费者媒体能力，是媒体可送达判定的唯一来源；
 *   <li>{@code branch}：只读本 Contributor 在当前 assistant 分支上的自定义状态与生效 Goal。
 * </ul>
 */
public record ToolExecutionContext(
    UUID invocationId,
    UUID threadId,
    UUID sessionId,
    UUID rootThreadId,
    UUID rootSessionId,
    Instant executedAt,
    TurnMediaCapabilities mediaCapabilities,
    BranchView branch,
    Optional<BoundEnvironment> environment) {

  public ToolExecutionContext {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(rootSessionId, "rootSessionId");
    Objects.requireNonNull(executedAt, "executedAt");
    Objects.requireNonNull(mediaCapabilities, "mediaCapabilities");
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(environment, "environment");
  }
}

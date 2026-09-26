package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 只读 Turn 计划预览入口：把尚未持久化的命令 batch 用与 {@link ThreadProcessor} 完全相同的纯 {@link TurnPlanBuilder} 装配为
 * INPUT candidate 历史。
 *
 * <p>命令在此按调用方给定的 sequence 起点获得合法 {@code nextCommandSequence + i}，{@code requestHash} 直接沿用调用方 （HTTP
 * 映射阶段对 raw payload 计算的 canonical hash），candidate Entry id 使用内存 UUID。本入口不接触 Store、不写任何 durable
 * 状态、不分配 store id、不消费任何 upload。
 */
public final class TurnPlanPreview {

  private TurnPlanPreview() {}

  /**
   * 构造 INPUT candidate EntryPath：source path 追加可选 normalization、TURN_START(INPUT) 与按序的 Message
   * Entry；SET_* 只冻结进 TURN_START.settings，不产生模型可见消息。批次缺少末尾 user-like 输入时与正式规划一致地确定性拒绝。
   *
   * @param nextCommandSequence 当前 Thread 的 nextCommandSequence；batch 按此起点获得连续合法 sequence
   * @param commands 尚未入队的命令 batch，顺序即产品顺序
   * @return candidate root-to-head path（candidate Entry id 仅为本次内存 UUID）
   */
  public static EntryPath inputCandidatePath(
      UUID threadId,
      EntryPath sourcePath,
      long nextCommandSequence,
      List<NewThreadCommand> commands,
      Instant now) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(sourcePath, "sourcePath");
    Objects.requireNonNull(commands, "commands");
    Objects.requireNonNull(now, "now");
    if (commands.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }
    if (nextCommandSequence <= 0) {
      throw new IllegalArgumentException("nextCommandSequence must be positive");
    }
    List<ThreadCommand> planned = new ArrayList<>(commands.size());
    for (int i = 0; i < commands.size(); i++) {
      NewThreadCommand command = Objects.requireNonNull(commands.get(i), "command");
      planned.add(
          new ThreadCommand(
              threadId,
              nextCommandSequence + i,
              command.payload(),
              command.idempotencyKey(),
              command.requestHash(),
              null,
              null,
              null,
              now));
    }
    return new TurnPlanBuilder()
        .build(threadId, sourcePath, TurnStartReason.INPUT, planned, UUID::randomUUID, now, null)
        .candidatePath();
  }
}

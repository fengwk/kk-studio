package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.api.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.api.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stop 回执组装：把每个节点收敛后的停止边界与被取消命令固化为 {@link StoppedThreadReceipt}，并组装最终 {@link StopResult} 与本地取消集合。
 *
 * <p>首次 Stop 的回执按 Thread UuidOrder 排序；replay 直接返回持久保存的集合与顺序，不以当前树重算。人工输入 {@code USER_MESSAGE} /
 * {@code GOAL} 额外进入草稿恢复列表，系统通知、CUSTOM_MESSAGE 与 SET_* 只计入取消数量。
 */
final class StopReceipts {

  private static final Comparator<StoppedThreadReceipt> RECEIPT_ORDER =
      Comparator.comparing(StoppedThreadReceipt::threadId, UuidOrder.COMPARATOR);

  private StopReceipts() {}

  static StoppedThreadReceipt nodeReceipt(
      ThreadState stoppedThread,
      UUID stopRequestId,
      UUID stoppedTurnEndEntryId,
      List<ThreadCommand> cancelledCommands) {
    Objects.requireNonNull(stoppedThread, "stoppedThread");
    Objects.requireNonNull(cancelledCommands, "cancelledCommands");
    return new StoppedThreadReceipt(
        stoppedThread.id(),
        stopRequestId,
        stoppedTurnEndEntryId,
        cancelledCommands.size(),
        cancelledInputs(cancelledCommands));
  }

  /** 首次 Stop 必须包含请求目标自身的回执；缺失说明收敛集合不完整。 */
  static void requireTargetReceipt(List<StoppedThreadReceipt> receipts, UUID targetThreadId) {
    Objects.requireNonNull(receipts, "receipts");
    Objects.requireNonNull(targetThreadId, "targetThreadId");
    if (receipts.stream().noneMatch(receipt -> receipt.threadId().equals(targetThreadId))) {
      throw new IllegalStateException("target thread receipt missing after Stop convergence");
    }
  }

  static StopControl.Commit commit(
      ThreadState target,
      List<StoppedThreadReceipt> receipts,
      Collection<UUID> driverExecutionIds,
      Collection<UUID> toolExecutionIds) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(receipts, "receipts");
    List<StoppedThreadReceipt> sorted = receipts.stream().sorted(RECEIPT_ORDER).toList();
    return new StopControl.Commit(
        new StopResult(false, target, sorted),
        List.copyOf(Objects.requireNonNull(driverExecutionIds, "driverExecutionIds")),
        List.copyOf(Objects.requireNonNull(toolExecutionIds, "toolExecutionIds")));
  }

  /** 既有 stopRequestId 的重放：返回持久保存的整批回执，不排序、不重算、不登记本地取消。 */
  static StopControl.Commit replay(ThreadState target, List<StoppedThreadReceipt> stored) {
    return new StopControl.Commit(new StopResult(true, target, stored), List.of(), List.of());
  }

  /** 按 sequence 升序还原被取消的人工输入（USER_MESSAGE 与 GOAL）；其余命令不退草稿。 */
  static List<CancelledThreadInput> cancelledInputs(List<ThreadCommand> cancelled) {
    List<CancelledThreadInput> inputs = new ArrayList<>();
    for (ThreadCommand command : cancelled) {
      if (command.payload() instanceof UserMessageCommandPayload
          || command.payload() instanceof GoalCommandPayload) {
        inputs.add(
            new CancelledThreadInput(
                command.sequence(), command.idempotencyKey(), command.payload()));
      }
    }
    return List.copyOf(inputs);
  }
}

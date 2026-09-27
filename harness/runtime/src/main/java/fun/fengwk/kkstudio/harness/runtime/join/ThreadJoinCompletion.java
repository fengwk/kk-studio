package fun.fengwk.kkstudio.harness.runtime.join;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.time.Instant;
import java.util.Objects;

/**
 * Join 结果交付的共享实现：把已匹配的 Join 投影为固定回执，渲染成 SYSTEM 提醒形态的 {@code subagent_result} 完成消息，并给出 待写入父 Thread
 * 的命令与 Join 交付事实。
 *
 * <p>Thread 空闲收敛、Stop 收尾结算与父 Thread 接受新输入时的挂起交付刷新共用同一实现，避免各控制面各自渲染出不同的消息形态或 不同的 requestHash。
 */
public final class ThreadJoinCompletion {

  /** 一次交付所需的父 Thread 命令与对应的 Join 交付事实；调用方负责写入命令、推进序列与更新 Join。 */
  public record Delivery(ThreadCommand command, ThreadJoin delivered) {}

  private ThreadJoinCompletion() {}

  /**
   * 判断父 Thread 是否处于暂停交付状态：head 已停在 STOPPED 停止边界，且本地没有排队中的真实用户输入。
   *
   * <p>只看 head STOPPED 会把“用户已重新排队输入、等待下一个 Turn 物化”的父 Thread 误判为暂停，使旧结果被无限冻结；只有 真正没有后续输入的 STOPPED 父
   * Thread 才应由后续用户输入恢复交付。
   */
  public static boolean isPaused(HarnessStore.Transaction tx, ThreadState parent) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(parent, "parent");
    if (!(tx.loadEntryPath(parent.headEntryId()).head().payload() instanceof TurnEndPayload end)
        || end.outcome() != TurnEndOutcome.STOPPED) {
      return false;
    }
    for (ThreadCommand command : tx.loadCommandsByThread(parent.id())) {
      if (command.state() == ThreadCommandState.QUEUED && isGenuineUserPayload(command)) {
        return false;
      }
    }
    return true;
  }

  /**
   * 投影 Join 结果并构造一条父 Thread 完成交付命令。
   *
   * <p>消息采用 {@link SystemReminder} 形态：交付是运行时 steering，而不是真实用户输入，因此不得参与“真实用户输入”判定，也不得解除 父 Thread
   * 的停止暂停。requestHash 统一由 {@link ThreadCommandPayloadJsonCodec} 计算。
   */
  public static Delivery buildDelivery(
      HarnessStore.Transaction tx,
      ThreadJoin matched,
      ThreadState parent,
      long sequence,
      Instant now) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(matched, "matched");
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(now, "now");
    ThreadJoinReceipt receipt =
        ThreadJoinProjector.INSTANCE
            .project(tx, matched)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "cannot project receipt for matched join " + matched.invocationId()));
    CustomMessageCommandPayload payload =
        new CustomMessageCommandPayload(SystemReminder.message(receipt.renderCompletionXml()));
    Instant parentMutationNow = HarnessStoreTime.notBefore(now, parent.updatedAt());
    Instant deliveryMutationNow =
        HarnessStoreTime.notBefore(parentMutationNow, matched.updatedAt());
    ThreadCommand command =
        new ThreadCommand(
            parent.id(),
            sequence,
            payload,
            matched.invocationId(),
            ThreadCommandPayloadJsonCodec.requestHash(payload),
            null,
            null,
            null,
            parentMutationNow);
    return new Delivery(command, matched.delivered(sequence, deliveryMutationNow));
  }

  private static boolean isGenuineUserPayload(ThreadCommand command) {
    return switch (command.payload()) {
      case UserMessageCommandPayload ignored -> true;
      case GoalCommandPayload ignored -> true;
      case CustomMessageCommandPayload custom -> !SystemReminder.isReminder(custom.message());
      default -> false;
    };
  }
}

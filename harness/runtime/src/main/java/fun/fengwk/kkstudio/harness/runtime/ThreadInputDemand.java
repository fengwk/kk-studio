package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;

import java.util.List;

/**
 * 本 Thread 自身输入需求的统一判据：QUEUED 的消息/系统通知，或已物化但尚未被 INPUT 接纳（sequence 超过 {@code inputThroughSequence}）的
 * APPLIED 系统通知。SET_* 不构成 turn 需求。
 *
 * <p>Processor 的 turn 规划与终止边界的 Join 结算必须使用同一规则，避免两个入口对「本 Thread 是否仍有待处理输入」产生分歧。判据只按本 Thread 自己的
 * Command 判定，绝不从共享/继承历史推断。
 */
public final class ThreadInputDemand {

  private ThreadInputDemand() {}

  /** 本 Thread 是否存在需要驱动的输入需求。 */
  public static boolean hasInputDemand(HarnessStore.Transaction tx, ThreadState thread) {
    if (hasQueuedDemand(tx.loadQueuedCommands(thread.id()))) {
      return true;
    }
    return hasUnconsumedNotification(
        tx.loadCommandsByThread(thread.id()), thread.inputThroughSequence());
  }

  /** queued 快照中是否存在需要驱动 turn 的输入（USER_MESSAGE、USER CUSTOM_MESSAGE 或系统 NOTIFICATION）。 */
  public static boolean hasQueuedDemand(List<ThreadCommand> queued) {
    for (ThreadCommand command : queued) {
      if (command.type().isMessage() || command.type().isNotification()) {
        return true;
      }
    }
    return false;
  }

  /** 已物化但位于输入水位之后的 APPLIED 系统通知是否仍待 INPUT 接纳。 */
  public static boolean hasUnconsumedNotification(
      List<ThreadCommand> commands, long inputThroughSequence) {
    for (ThreadCommand command : commands) {
      if (command.sequence() > inputThroughSequence
          && command.state() == ThreadCommandState.APPLIED
          && command.type().isNotification()) {
        return true;
      }
    }
    return false;
  }
}

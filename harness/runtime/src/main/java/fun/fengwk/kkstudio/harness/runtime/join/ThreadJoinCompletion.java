package fun.fengwk.kkstudio.harness.runtime.join;

import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Join 结果交付的共享实现：把已冻结的 Join 投影为固定回执，构造一条 {@link NotificationKind#SUBAGENT_RESULT} 系统通知命令交付给父
 * Thread，并给出待写入的 Join 交付事实。
 *
 * <p>执行终止边界、Join 结果冻结与父通知接受在同一事务提交；父为 STOPPED 时由调用方把通知直接固化到历史而不唤醒模型。通知内容与来源在冻结时
 * 确定，重复交付不从子 Thread 最新 head 重建旧结果。
 */
public final class ThreadJoinCompletion {

  /** 一次交付所需的父 Thread 命令与对应的 Join 交付事实；调用方负责写入命令、推进序列与更新 Join。 */
  public record Delivery(ThreadCommand command, ThreadJoin delivered) {}

  private ThreadJoinCompletion() {}

  /**
   * 投影 Join 结果并构造一条父 Thread 完成通知命令。
   *
   * <p>通知 identity 由 invocationId 确定派生，重复交付同一 Join 得到同一 notificationId（幂等）。
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
    NotificationCommandPayload payload =
        new NotificationCommandPayload(
            notificationId(matched.invocationId()),
            NotificationKind.SUBAGENT_RESULT,
            matched.childThreadId(),
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(new TextMessageContent(receipt.renderCompletionXml()))));
    Instant parentMutationNow = HarnessStoreTime.notBefore(now, parent.updatedAt());
    Instant deliveryMutationNow =
        HarnessStoreTime.notBefore(parentMutationNow, matched.updatedAt());
    ThreadCommand command =
        new ThreadCommand(
            parent.id(),
            sequence,
            payload,
            payload.notificationId(),
            ThreadCommandPayloadJsonCodec.requestHash(payload),
            null,
            null,
            null,
            parentMutationNow);
    return new Delivery(command, matched.delivered(sequence, deliveryMutationNow));
  }

  /** 由 Join invocationId 确定派生的通知 identity（同一 Join 重复交付幂等）。 */
  public static UUID notificationId(UUID invocationId) {
    Objects.requireNonNull(invocationId, "invocationId");
    return UUID.nameUUIDFromBytes(
        ("subagent-result:" + invocationId).getBytes(StandardCharsets.UTF_8));
  }
}

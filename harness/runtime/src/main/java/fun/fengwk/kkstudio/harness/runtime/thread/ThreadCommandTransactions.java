package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Thread command 用例的原子持久化端口。 */
public interface ThreadCommandTransactions {
  HarnessThread createThread(String title, Instant now);

  HarnessThread updateHead(
      long threadId, long expectedExecutionEpoch, long headEntryId, Instant now);

  Optional<EnqueueResult> findExistingInput(long threadId, String idempotencyKey);

  EnqueueResult enqueue(
      long threadId,
      ThreadInputPayload payload,
      String idempotencyKey,
      long expectedExecutionEpoch,
      Instant now);

  /**
   * 在已锁定 Thread + 当前 epoch 下原子 stop：发现当前 epoch 的可 partial durable 状态、按 canonical planner 的 debt
   * 判定决定是否追加 {@code ASSISTANT_ABORTED} 终止 entry（仅含安全 text/thinking），否则追加 {@code ASSISTANT_ERROR}
   * cancellation barrier；递增 execution_epoch、清理 queued inputs 与 safe invocations；该方法必须 atomic。
   *
   * <p>外部 {@code /stop} HTTP DTO 不变；适配器在已持有 Thread 行锁的同一事务内自行发现 invocation。本方法不写 durable assistant
   * entry 的工具调用片段，绝不创建 ToolInvocation，绝不重建旧 debt 的 ModelInvocation。
   */
  StopResult stop(long threadId, long expectedExecutionEpoch, Instant now);

  record EnqueueResult(ThreadInput input) {
    public EnqueueResult {
      Objects.requireNonNull(input, "input");
    }
  }

  record StopResult(long executionEpoch, List<ThreadInput> cancelledInputs) {
    public StopResult {
      cancelledInputs = List.copyOf(Objects.requireNonNull(cancelledInputs, "cancelledInputs"));
    }
  }
}

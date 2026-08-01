package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;

import java.util.List;
import java.util.Objects;

/**
 * TURN_INPUT_BATCH：turn 启动时 snapshot 中同 Thread、QUEUED、sequence 严格递增的全部 Input。
 *
 * <p>empty 列表非法；消息输入按 mailbox 顺序批量应用。Batch 只保存 canonical {@link #inputs()}，不维护可从列表派生的重复字段。
 */
public record TurnInputBatch(long threadId, List<ThreadInput> inputs) {

  public TurnInputBatch {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    Objects.requireNonNull(inputs, "inputs");
    if (inputs.isEmpty()) {
      throw new IllegalArgumentException("turn input batch must contain at least one input");
    }
    inputs = List.copyOf(inputs);

    long previousSequence = 0L;
    for (ThreadInput input : inputs) {
      if (input.threadId() != threadId) {
        throw new IllegalArgumentException(
            "input threadId " + input.threadId() + " does not match batch threadId " + threadId);
      }
      if (input.status() != InputStatus.QUEUED) {
        throw new IllegalArgumentException(
            "input must be QUEUED but was " + input.status() + " at sequence " + input.sequence());
      }
      if (input.sequence() <= previousSequence) {
        throw new IllegalArgumentException(
            "input sequence must be strictly increasing at "
                + input.sequence()
                + " after "
                + previousSequence);
      }
      previousSequence = input.sequence();
    }
  }
}

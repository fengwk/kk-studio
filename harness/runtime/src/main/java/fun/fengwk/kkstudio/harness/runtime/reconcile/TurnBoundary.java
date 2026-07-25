package fun.fengwk.kkstudio.harness.runtime.reconcile;

import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * TURN_BOUNDARY：同 Thread、QUEUED、sequence 严格递增、零或多个 config 后接至多一条 message 的有序 Input 切片。
 *
 * <p>empty 列表非法；message-only 与 config-only 均合法。{@link #configInputs()}、 {@link
 * #messageInput()}、{@link #lastSequence()} 与 {@link #hasMessage()} 全部从 {@link #inputs()}
 * 派生；不在多个字段之间维护不变量。
 */
public record TurnBoundary(long threadId, List<ThreadInput> inputs) {

  public TurnBoundary {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    Objects.requireNonNull(inputs, "inputs");
    if (inputs.isEmpty()) {
      throw new IllegalArgumentException("turn boundary must contain at least one input");
    }
    inputs = List.copyOf(inputs);

    long previousSequence = 0L;
    boolean sawMessage = false;
    for (ThreadInput input : inputs) {
      if (input.threadId() != threadId) {
        throw new IllegalArgumentException(
            "input threadId " + input.threadId() + " does not match boundary threadId " + threadId);
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
      ThreadInputType type = input.type();
      if (!type.isConfig() && !type.isMessage()) {
        throw new IllegalArgumentException("unsupported input type: " + type);
      }
      if (sawMessage) {
        throw new IllegalArgumentException(
            "input at sequence " + input.sequence() + " appears after the boundary message");
      }
      if (type.isMessage()) {
        sawMessage = true;
      }
      previousSequence = input.sequence();
    }
  }

  /** config 前缀的不可变视图。message-only 边界返回空列表。 */
  public List<ThreadInput> configInputs() {
    List<ThreadInput> result = new ArrayList<>();
    for (ThreadInput input : inputs) {
      if (input.type().isMessage()) {
        break;
      }
      result.add(input);
    }
    return List.copyOf(result);
  }

  /** 末端 message（如有）。config-only 边界返回 {@link Optional#empty()}。 */
  public Optional<ThreadInput> messageInput() {
    ThreadInput last = inputs.get(inputs.size() - 1);
    return last.type().isMessage() ? Optional.of(last) : Optional.empty();
  }

  /** 该边界覆盖到的最大 sequence。 */
  public long lastSequence() {
    return inputs.get(inputs.size() - 1).sequence();
  }

  /** 是否包含产生 response debt 的 message。 */
  public boolean hasMessage() {
    return inputs.get(inputs.size() - 1).type().isMessage();
  }
}

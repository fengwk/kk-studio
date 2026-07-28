package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 从 Reconcile snapshot 中的 queued Inputs 选取一个 TURN_BOUNDARY。
 *
 * <p>先对完整 queued slice 做防御性校验（同 Thread、QUEUED、sequence 严格递增、仅 config/message 类型），全部通过后才截取首个 config
 * 前缀与首条 message 构成边界；其后的合法输入留给下一轮 activation。任意不合法输入（跨 Thread、非 QUEUED、序列号非递增、未知类型）都会令 selector 直接抛
 * {@link IllegalArgumentException}，并不返回任何 boundary。
 */
public final class TurnBoundarySelector {

  private TurnBoundarySelector() {}

  /**
   * 从 queued Inputs 选取一个边界。
   *
   * @param ownership 当前 reconcile ownership，用于 Thread 校验
   * @param queuedInputs 已按 sequence 升序排列的 QUEUED Inputs
   * @return 当 queuedInputs 非空时返回边界；否则返回 {@link Optional#empty()}
   * @throws IllegalArgumentException 当 queuedInputs 任一元素不满足同 Thread / QUEUED / 严格递增 /
   *     config+message 类型的约束
   */
  public static Optional<TurnBoundary> select(
      ThreadOwnership ownership, List<ThreadInput> queuedInputs) {
    Objects.requireNonNull(ownership, "ownership");
    Objects.requireNonNull(queuedInputs, "queuedInputs");
    if (queuedInputs.isEmpty()) {
      return Optional.empty();
    }

    long previousSequence = 0L;
    for (ThreadInput input : queuedInputs) {
      if (input.threadId() != ownership.threadId()) {
        throw new IllegalArgumentException(
            "queued input threadId "
                + input.threadId()
                + " does not match ownership threadId "
                + ownership.threadId());
      }
      if (input.status() != InputStatus.QUEUED) {
        throw new IllegalArgumentException(
            "queued input must be QUEUED but was "
                + input.status()
                + " at sequence "
                + input.sequence());
      }
      if (input.sequence() <= previousSequence) {
        throw new IllegalArgumentException(
            "queued input sequence must be strictly increasing at "
                + input.sequence()
                + " after "
                + previousSequence);
      }
      ThreadInputType type = input.type();
      if (!type.isConfig() && !type.isMessage()) {
        throw new IllegalArgumentException("unsupported input type: " + type);
      }
      previousSequence = input.sequence();
    }

    List<ThreadInput> boundaryInputs = new ArrayList<>();
    for (ThreadInput input : queuedInputs) {
      boundaryInputs.add(input);
      if (input.type().isMessage()) {
        break;
      }
    }
    return Optional.of(new TurnBoundary(ownership.threadId(), boundaryInputs));
  }
}

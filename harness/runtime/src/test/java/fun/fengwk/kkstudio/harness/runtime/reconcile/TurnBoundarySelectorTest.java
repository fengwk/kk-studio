package fun.fengwk.kkstudio.harness.runtime.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.util.List;
import java.util.Optional;

/**
 * TurnBoundarySelector 必须先对完整 queued slice 做防御性校验（任何位置的不合法输入都令 selector 抛
 * IllegalArgumentException），然后再选择首个 config 前缀与首条 message。
 */
class TurnBoundarySelectorTest {

  private static final ThreadOwnership OWNERSHIP = ReconcileTestSupport.ownership(1L, 0L, "tok");

  @Test
  void emptyInputsReturnsEmpty() {
    Optional<TurnBoundary> result = TurnBoundarySelector.select(OWNERSHIP, List.of());
    assertFalse(result.isPresent());
  }

  @Test
  void rejectsNullInputs() {
    assertThrows(NullPointerException.class, () -> TurnBoundarySelector.select(OWNERSHIP, null));
  }

  @Test
  void configOnlyBatchContainsAllConfigPrefix() {
    List<ThreadInput> inputs =
        List.of(
            ReconcileTestSupport.input(1L, 1L, ThreadInputType.SET_MODEL),
            ReconcileTestSupport.input(1L, 2L, ThreadInputType.SET_YOLO));

    Optional<TurnBoundary> result = TurnBoundarySelector.select(OWNERSHIP, inputs);

    assertTrue(result.isPresent());
    TurnBoundary boundary = result.get();
    assertFalse(boundary.hasMessage());
    assertEquals(2L, boundary.lastSequence());
    assertEquals(2, boundary.configInputs().size());
    assertEquals(inputs, boundary.inputs());
  }

  @Test
  void configPlusMessageSelectsPrefixAndOneMessage() {
    List<ThreadInput> inputs =
        List.of(
            ReconcileTestSupport.input(1L, 1L, ThreadInputType.SET_MODEL),
            ReconcileTestSupport.input(1L, 2L, ThreadInputType.USER_MESSAGE),
            // 后继合法输入留给下一轮 activation。
            ReconcileTestSupport.input(1L, 3L, ThreadInputType.USER_MESSAGE),
            ReconcileTestSupport.input(1L, 4L, ThreadInputType.SET_AGENT));

    Optional<TurnBoundary> result = TurnBoundarySelector.select(OWNERSHIP, inputs);

    assertTrue(result.isPresent());
    TurnBoundary boundary = result.get();
    assertTrue(boundary.hasMessage());
    assertEquals(2L, boundary.lastSequence());
    assertEquals(1, boundary.configInputs().size());
    assertEquals(inputs.get(1).sequence(), boundary.messageInput().orElseThrow().sequence());
  }

  @Test
  void validConfigAfterFirstMessageIsLeftForNextActivation() {
    ThreadInput msg = ReconcileTestSupport.input(1L, 2L, ThreadInputType.USER_MESSAGE);
    ThreadInput trailing = ReconcileTestSupport.input(1L, 3L, ThreadInputType.SET_MODEL);
    List<ThreadInput> reordered = List.of(msg, trailing);

    Optional<TurnBoundary> result = TurnBoundarySelector.select(OWNERSHIP, reordered);

    assertTrue(result.isPresent());
    TurnBoundary boundary = result.get();
    assertTrue(boundary.hasMessage());
    assertEquals(2L, boundary.lastSequence());
    assertTrue(boundary.configInputs().isEmpty());
  }

  @Test
  void rejectsCrossThreadQueuedInputs() {
    ThreadInput foreign = ReconcileTestSupport.input(2L, 1L, ThreadInputType.USER_MESSAGE);
    assertThrows(
        IllegalArgumentException.class,
        () -> TurnBoundarySelector.select(OWNERSHIP, List.of(foreign)));
  }

  @Test
  void rejectsNonQueuedStatus() {
    ThreadInput applied =
        new ThreadInput(
            1L,
            1L,
            1L,
            ThreadInputType.USER_MESSAGE,
            payload(),
            "key",
            InputStatus.APPLIED,
            ReconcileTestSupport.NOW,
            ReconcileTestSupport.NOW.plusSeconds(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> TurnBoundarySelector.select(OWNERSHIP, List.of(applied)));
  }

  @Test
  void rejectsNonMonotonicSequences() {
    List<ThreadInput> inputs =
        List.of(
            ReconcileTestSupport.input(1L, 2L, ThreadInputType.SET_MODEL),
            ReconcileTestSupport.input(1L, 1L, ThreadInputType.USER_MESSAGE));
    assertThrows(
        IllegalArgumentException.class, () -> TurnBoundarySelector.select(OWNERSHIP, inputs));
  }

  @Test
  void crossThreadInputAfterFirstMessageStillRejects() {
    // 整片 queued slice 在 message 之后仍出现一个跨 Thread 的输入，selector 必须拒绝。
    List<ThreadInput> inputs =
        List.of(
            ReconcileTestSupport.input(1L, 1L, ThreadInputType.SET_MODEL),
            ReconcileTestSupport.input(1L, 2L, ThreadInputType.USER_MESSAGE),
            ReconcileTestSupport.input(2L, 3L, ThreadInputType.SET_MODEL));
    assertThrows(
        IllegalArgumentException.class, () -> TurnBoundarySelector.select(OWNERSHIP, inputs));
  }

  @Test
  void nonQueuedInputAfterFirstMessageStillRejects() {
    ThreadInputPayload payload = () -> ThreadInputType.SET_MODEL;
    ThreadInput applied =
        new ThreadInput(
            2L,
            1L,
            3L,
            ThreadInputType.SET_MODEL,
            payload,
            "k",
            InputStatus.APPLIED,
            ReconcileTestSupport.NOW,
            ReconcileTestSupport.NOW.plusSeconds(1));
    List<ThreadInput> inputs =
        List.of(
            ReconcileTestSupport.input(1L, 1L, ThreadInputType.SET_MODEL),
            ReconcileTestSupport.input(1L, 2L, ThreadInputType.USER_MESSAGE),
            applied);
    assertThrows(
        IllegalArgumentException.class, () -> TurnBoundarySelector.select(OWNERSHIP, inputs));
  }

  @Test
  void nonMonotonicSequenceAfterFirstMessageStillRejects() {
    List<ThreadInput> inputs =
        List.of(
            ReconcileTestSupport.input(1L, 1L, ThreadInputType.SET_MODEL),
            ReconcileTestSupport.input(1L, 2L, ThreadInputType.USER_MESSAGE),
            // sequence 1 < 2：违反严格递增。
            ReconcileTestSupport.input(1L, 1L, ThreadInputType.SET_MODEL));
    assertThrows(
        IllegalArgumentException.class, () -> TurnBoundarySelector.select(OWNERSHIP, inputs));
  }

  private static ThreadInputPayload payload() {
    return () -> ThreadInputType.USER_MESSAGE;
  }
}

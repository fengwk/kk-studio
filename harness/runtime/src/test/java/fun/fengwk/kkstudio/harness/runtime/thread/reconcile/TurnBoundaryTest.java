package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link TurnBoundary} 构造约束、派生字段（configInputs / messageInput / lastSequence）以及 message-only /
 * config-only 合法性测试。
 */
class TurnBoundaryTest {

  @Test
  void rejectsNonPositiveThreadId() {
    assertThrows(
        IllegalArgumentException.class, () -> new TurnBoundary(0L, List.of(config(1L, 1L))));
  }

  @Test
  void rejectsEmptyInputs() {
    assertThrows(IllegalArgumentException.class, () -> new TurnBoundary(1L, List.of()));
  }

  @Test
  void rejectsNullInputs() {
    assertThrows(NullPointerException.class, () -> new TurnBoundary(1L, null));
  }

  @Test
  void configOnlyBoundaryHasNoMessage() {
    ThreadInput cfg1 = config(1L, 1L);
    ThreadInput cfg2 = config(1L, 2L);
    TurnBoundary boundary = new TurnBoundary(1L, List.of(cfg1, cfg2));

    assertFalse(boundary.hasMessage());
    assertEquals(Optional.empty(), boundary.messageInput());
    assertEquals(List.of(cfg1, cfg2), boundary.configInputs());
    assertEquals(2L, boundary.lastSequence());
  }

  @Test
  void configPlusMessageBoundaryReportsMessageFlag() {
    ThreadInput cfg = config(1L, 1L);
    ThreadInput msg = message(1L, 2L);
    TurnBoundary boundary = new TurnBoundary(1L, List.of(cfg, msg));

    assertTrue(boundary.hasMessage());
    assertEquals(Optional.of(msg), boundary.messageInput());
    assertEquals(List.of(cfg), boundary.configInputs());
    assertEquals(2L, boundary.lastSequence());
    assertEquals(List.of(cfg, msg), boundary.inputs());
  }

  @Test
  void messageOnlyBoundaryIsValid() {
    ThreadInput msg = message(1L, 5L);
    TurnBoundary boundary = new TurnBoundary(1L, List.of(msg));

    assertTrue(boundary.hasMessage());
    assertEquals(Optional.of(msg), boundary.messageInput());
    assertTrue(boundary.configInputs().isEmpty());
    assertEquals(5L, boundary.lastSequence());
  }

  @Test
  void configListIsDefensivelyCopied() {
    ArrayList<ThreadInput> mutable = new ArrayList<>();
    mutable.add(config(1L, 1L));
    TurnBoundary boundary = new TurnBoundary(1L, mutable);
    mutable.clear();

    assertEquals(1, boundary.configInputs().size());
  }

  @Test
  void rejectsCrossThreadInput() {
    ThreadInput foreign = config(2L, 1L);
    assertThrows(IllegalArgumentException.class, () -> new TurnBoundary(1L, List.of(foreign)));
  }

  @Test
  void rejectsNonQueuedInput() {
    ThreadInputPayload payload = () -> ThreadInputType.USER_MESSAGE;
    ThreadInput applied =
        new ThreadInput(
            1L,
            1L,
            1L,
            ThreadInputType.USER_MESSAGE,
            payload,
            "k",
            InputStatus.APPLIED,
            ReconcileTestSupport.NOW,
            ReconcileTestSupport.NOW.plusSeconds(1));
    assertThrows(IllegalArgumentException.class, () -> new TurnBoundary(1L, List.of(applied)));
  }

  @Test
  void rejectsNonMonotonicSequences() {
    ThreadInput first = config(1L, 2L);
    ThreadInput second = config(1L, 1L);
    assertThrows(
        IllegalArgumentException.class, () -> new TurnBoundary(1L, List.of(first, second)));
  }

  @Test
  void rejectsInputAfterFinalMessage() {
    ThreadInput msg = message(1L, 2L);
    ThreadInput trailing = config(1L, 3L);
    assertThrows(
        IllegalArgumentException.class, () -> new TurnBoundary(1L, List.of(msg, trailing)));
  }

  private static ThreadInput config(long threadId, long sequence) {
    return ReconcileTestSupport.input(threadId, sequence, ThreadInputType.SET_MODEL);
  }

  private static ThreadInput message(long threadId, long sequence) {
    return ReconcileTestSupport.input(threadId, sequence, ThreadInputType.USER_MESSAGE);
  }
}

package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.util.ArrayList;
import java.util.List;

/** {@link TurnInputBatch} 的构造约束、不可变性以及任意 config/message 排列测试。 */
class TurnInputBatchTest {

  @Test
  void rejectsNonPositiveThreadId() {
    assertThrows(
        IllegalArgumentException.class, () -> new TurnInputBatch(0L, List.of(config(1L, 1L))));
  }

  @Test
  void rejectsEmptyInputs() {
    assertThrows(IllegalArgumentException.class, () -> new TurnInputBatch(1L, List.of()));
  }

  @Test
  void rejectsNullInputs() {
    assertThrows(NullPointerException.class, () -> new TurnInputBatch(1L, null));
  }

  @Test
  void acceptsConfigOnlyBatch() {
    ThreadInput cfg1 = config(1L, 1L);
    ThreadInput cfg2 = config(1L, 2L);
    TurnInputBatch batch = new TurnInputBatch(1L, List.of(cfg1, cfg2));

    assertEquals(List.of(cfg1, cfg2), batch.inputs());
  }

  @Test
  void allowsArbitraryConfigAndMessageOrderAndMultipleMessages() {
    ThreadInput cfg = config(1L, 1L);
    ThreadInput firstMessage = message(1L, 2L);
    ThreadInput secondMessage = message(1L, 3L);
    ThreadInput trailingConfig = config(1L, 4L);
    List<ThreadInput> inputs = List.of(cfg, firstMessage, secondMessage, trailingConfig);
    TurnInputBatch batch = new TurnInputBatch(1L, inputs);

    assertEquals(inputs, batch.inputs());
  }

  @Test
  void messageOnlyBatchIsValid() {
    ThreadInput msg = message(1L, 5L);
    TurnInputBatch batch = new TurnInputBatch(1L, List.of(msg));

    assertEquals(List.of(msg), batch.inputs());
  }

  @Test
  void inputsAreDefensivelyCopiedAndImmutable() {
    ArrayList<ThreadInput> mutable = new ArrayList<>();
    mutable.add(config(1L, 1L));
    TurnInputBatch batch = new TurnInputBatch(1L, mutable);
    mutable.clear();

    assertEquals(1, batch.inputs().size());
    assertThrows(UnsupportedOperationException.class, () -> batch.inputs().add(message(1L, 2L)));
  }

  @Test
  void rejectsCrossThreadInput() {
    ThreadInput foreign = config(2L, 1L);
    assertThrows(IllegalArgumentException.class, () -> new TurnInputBatch(1L, List.of(foreign)));
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
    assertThrows(IllegalArgumentException.class, () -> new TurnInputBatch(1L, List.of(applied)));
  }

  @Test
  void rejectsNonMonotonicSequences() {
    ThreadInput first = config(1L, 2L);
    ThreadInput second = config(1L, 1L);
    assertThrows(
        IllegalArgumentException.class, () -> new TurnInputBatch(1L, List.of(first, second)));
  }

  private static ThreadInput config(long threadId, long sequence) {
    return ReconcileTestSupport.input(threadId, sequence, ThreadInputType.SET_MODEL);
  }

  private static ThreadInput message(long threadId, long sequence) {
    return ReconcileTestSupport.input(threadId, sequence, ThreadInputType.USER_MESSAGE);
  }
}

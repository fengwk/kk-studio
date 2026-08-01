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

/** {@link TurnInputBatch} 的构造约束、不可变性以及 USER/CUSTOM message 排列测试。 */
class TurnInputBatchTest {

  @Test
  void rejectsNonPositiveThreadId() {
    assertThrows(
        IllegalArgumentException.class, () -> new TurnInputBatch(0L, List.of(custom(1L, 1L))));
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
  void acceptsUserAndCustomMessageBatch() {
    ThreadInput user = user(1L, 1L);
    ThreadInput custom = custom(1L, 2L);
    TurnInputBatch batch = new TurnInputBatch(1L, List.of(user, custom));

    assertEquals(List.of(user, custom), batch.inputs());
  }

  @Test
  void allowsUserAndCustomMessagesInMailboxOrder() {
    ThreadInput firstMessage = user(1L, 1L);
    ThreadInput secondMessage = custom(1L, 2L);
    ThreadInput thirdMessage = user(1L, 3L);
    List<ThreadInput> inputs = List.of(firstMessage, secondMessage, thirdMessage);
    TurnInputBatch batch = new TurnInputBatch(1L, inputs);

    assertEquals(inputs, batch.inputs());
  }

  @Test
  void messageOnlyBatchIsValid() {
    ThreadInput msg = user(1L, 5L);
    TurnInputBatch batch = new TurnInputBatch(1L, List.of(msg));

    assertEquals(List.of(msg), batch.inputs());
  }

  @Test
  void inputsAreDefensivelyCopiedAndImmutable() {
    ArrayList<ThreadInput> mutable = new ArrayList<>();
    mutable.add(user(1L, 1L));
    TurnInputBatch batch = new TurnInputBatch(1L, mutable);
    mutable.clear();

    assertEquals(1, batch.inputs().size());
    assertThrows(UnsupportedOperationException.class, () -> batch.inputs().add(user(1L, 2L)));
  }

  @Test
  void rejectsCrossThreadInput() {
    ThreadInput foreign = user(2L, 1L);
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
    ThreadInput first = user(1L, 2L);
    ThreadInput second = custom(1L, 1L);
    assertThrows(
        IllegalArgumentException.class, () -> new TurnInputBatch(1L, List.of(first, second)));
  }

  private static ThreadInput custom(long threadId, long sequence) {
    return ReconcileTestSupport.input(threadId, sequence, ThreadInputType.CUSTOM_MESSAGE);
  }

  private static ThreadInput user(long threadId, long sequence) {
    return ReconcileTestSupport.input(threadId, sequence, ThreadInputType.USER_MESSAGE);
  }
}

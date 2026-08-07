package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** 原子入队 batch 的 CAS 字段、顺序与 client command ID 不变量。 */
class ThreadCommandBatchTest {

  @Test
  void preservesOrderedCommandsAndDefensivelyCopies() {
    NewThreadCommand first = new NewThreadCommand(new SetAgentCommandPayload("coding"), "client-1");
    NewThreadCommand second = new NewThreadCommand(new SetYoloCommandPayload(true), "client-2");
    ArrayList<NewThreadCommand> source = new ArrayList<>(List.of(first, second));
    ThreadCommandBatch batch = new ThreadCommandBatch(7L, 42L, 10L, source);
    source.clear();

    assertEquals(7L, batch.threadId());
    assertEquals(42L, batch.expectedHeadEntryId());
    assertEquals(10L, batch.expectedNextCommandSequence());
    assertEquals(List.of(first, second), batch.commands());
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            batch
                .commands()
                .add(new NewThreadCommand(new SetYoloCommandPayload(false), "client-3")));
  }

  @Test
  void rejectsInvalidCasAndDuplicateClientCommandIds() {
    NewThreadCommand first = new NewThreadCommand(new SetAgentCommandPayload("coding"), "same");
    NewThreadCommand duplicate = new NewThreadCommand(new SetYoloCommandPayload(true), "same");
    assertThrows(
        IllegalArgumentException.class, () -> new ThreadCommandBatch(0L, 1L, 1L, List.of(first)));
    assertThrows(
        IllegalArgumentException.class, () -> new ThreadCommandBatch(1L, 0L, 1L, List.of(first)));
    assertThrows(
        IllegalArgumentException.class, () -> new ThreadCommandBatch(1L, 1L, 0L, List.of(first)));
    assertThrows(
        IllegalArgumentException.class, () -> new ThreadCommandBatch(1L, 1L, 1L, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCommandBatch(1L, 1L, 1L, List.of(first, duplicate)));
    assertThrows(NullPointerException.class, () -> new ThreadCommandBatch(1L, 1L, 1L, null));
  }
}

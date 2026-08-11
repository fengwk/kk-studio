package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** 原子入队 batch 的 CAS 字段、顺序与 client command ID 不变量。 */
class ThreadCommandBatchTest {

  @Test
  void preservesOrderedCommandsAndDefensivelyCopies() {
    NewThreadCommand first = new NewThreadCommand(new SetAgentCommandPayload("coding"), id(1L));
    NewThreadCommand second = new NewThreadCommand(new SetYoloCommandPayload(true), id(2L));
    ArrayList<NewThreadCommand> source = new ArrayList<>(List.of(first, second));
    ThreadCommandBatch batch = new ThreadCommandBatch(id(7L), id(42L), 10L, source);
    source.clear();

    assertEquals(id(7L), batch.threadId());
    assertEquals(id(42L), batch.expectedHeadEntryId());
    assertEquals(10L, batch.expectedNextCommandSequence());
    assertEquals(List.of(first, second), batch.commands());
    assertThrows(
        UnsupportedOperationException.class,
        () -> batch.commands().add(new NewThreadCommand(new SetYoloCommandPayload(false), id(3L))));
  }

  @Test
  void rejectsInvalidCasAndDuplicateClientCommandIds() {
    NewThreadCommand first = new NewThreadCommand(new SetAgentCommandPayload("coding"), id(1L));
    NewThreadCommand duplicate = new NewThreadCommand(new SetYoloCommandPayload(true), id(1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCommandBatch(id(1L), id(1L), 0L, List.of(first)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCommandBatch(id(1L), id(1L), 1L, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCommandBatch(id(1L), id(1L), 1L, List.of(first, duplicate)));
    assertThrows(
        NullPointerException.class, () -> new ThreadCommandBatch(id(1L), id(1L), 1L, null));
  }
}

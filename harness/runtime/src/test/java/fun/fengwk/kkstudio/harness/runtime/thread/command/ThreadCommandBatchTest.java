package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 原子入队 batch 的 CAS 字段、顺序与 client command ID 不变量。 */
class ThreadCommandBatchTest {

  @Test
  void preservesOrderedCommandsAndDefensivelyCopies() {
    NewThreadCommand first = command(new SetAgentCommandPayload("coding"), id(1L));
    NewThreadCommand second = command(new SetEnvironmentCommandPayload(null), id(2L));
    ArrayList<NewThreadCommand> source = new ArrayList<>(List.of(first, second));
    ThreadCommandBatch batch = new ThreadCommandBatch(id(7L), id(42L), 10L, source);
    source.clear();

    assertEquals(id(7L), batch.threadId());
    assertEquals(id(42L), batch.expectedHeadEntryId());
    assertEquals(10L, batch.expectedNextCommandSequence());
    assertEquals(List.of(first, second), batch.commands());
    assertThrows(
        UnsupportedOperationException.class,
        () -> batch.commands().add(command(new SetAgentCommandPayload("other"), id(3L))));
  }

  @Test
  void rejectsInvalidCasAndDuplicateClientCommandIds() {
    NewThreadCommand first = command(new SetAgentCommandPayload("coding"), id(1L));
    NewThreadCommand duplicate = command(new SetEnvironmentCommandPayload(null), id(1L));
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

  private static NewThreadCommand command(ThreadCommandPayload payload, UUID clientCommandId) {
    return new NewThreadCommand(
        payload, clientCommandId, ThreadCommandPayloadJsonCodec.requestHash(payload));
  }
}

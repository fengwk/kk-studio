package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * StopCommand / StopResult record 校验：每一种被拒绝的形态都精确一致；并通过真实 Stop 使用最长的允许 external id 来验证 STOPPED
 * TURN_END 上 256 字符的 closeRequestId 上限 （复合 thread-scope key 必须仍能容纳）。
 */
class StopRecordsTest {

  private static final ThreadState THREAD =
      new ThreadState(1, 2, false, 1, 0, Instant.ofEpochMilli(1), Instant.ofEpochMilli(1));

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(Instant.ofEpochMilli(3_000), ZoneOffset.UTC));
  }

  @Test
  void stopCommandRejectsInvalidShapes() {
    assertThrows(IllegalArgumentException.class, () -> new StopCommand(0, "stop-1", 0));
    assertThrows(IllegalArgumentException.class, () -> new StopCommand(-1, "stop-1", 0));
    assertThrows(IllegalArgumentException.class, () -> new StopCommand(1, "", 0));
    assertThrows(IllegalArgumentException.class, () -> new StopCommand(1, "  ", 0));
    assertThrows(IllegalArgumentException.class, () -> new StopCommand(1, null, 0));
    assertThrows(IllegalArgumentException.class, () -> new StopCommand(1, " stop", 0));
    assertThrows(IllegalArgumentException.class, () -> new StopCommand(1, "stop ", 0));
    assertThrows(IllegalArgumentException.class, () -> new StopCommand(1, "s".repeat(129), 0));
    assertThrows(IllegalArgumentException.class, () -> new StopCommand(1, "stop-1", -1));
  }

  @Test
  void stopCommandAccessorsRoundTrip() {
    StopCommand command = new StopCommand(7, "stop-1", 42);
    assertEquals(7L, command.threadId());
    assertEquals("stop-1", command.stopRequestId());
    assertEquals(42L, command.expectedRevision());
  }

  @Test
  void stopResultRejectsInvalidShapes() {
    assertThrows(NullPointerException.class, () -> new StopResult(null, THREAD, null, 0));
    assertThrows(
        NullPointerException.class, () -> new StopResult(StopResult.Status.IDLE, null, null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StopResult(StopResult.Status.IDLE, THREAD, null, -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StopResult(StopResult.Status.IDLE, THREAD, 3L, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StopResult(StopResult.Status.STOPPED, THREAD, null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StopResult(StopResult.Status.STOPPED, THREAD, 0L, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StopResult(StopResult.Status.REPLAYED, THREAD, null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StopResult(StopResult.Status.REPLAYED, THREAD, 3L, 1));
  }

  @Test
  void stopResultValidShapesRoundTrip() {
    StopResult idle = new StopResult(StopResult.Status.IDLE, THREAD, null, 0);
    assertEquals(StopResult.Status.IDLE, idle.status());
    assertEquals(THREAD, idle.thread());
    assertNull(idle.stoppedTurnEndEntryId());
    assertEquals(0, idle.cancelledCommandCount());

    StopResult stopped = new StopResult(StopResult.Status.STOPPED, THREAD, 3L, 2);
    assertEquals(3L, stopped.stoppedTurnEndEntryId());
    assertEquals(2, stopped.cancelledCommandCount());
  }

  @Test
  void stopCommitRejectsInvalidLocalCancellationMetadata() {
    StopResult idle = new StopResult(StopResult.Status.IDLE, THREAD, null, 0);
    assertThrows(IllegalArgumentException.class, () -> new StopControl.Commit(idle, 0L, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new StopControl.Commit(idle, null, List.of(0L)));
    assertThrows(
        IllegalArgumentException.class, () -> new StopControl.Commit(idle, 1L, List.of(2L)));
    assertThrows(NullPointerException.class, () -> new StopControl.Commit(null, null, List.of()));
    assertThrows(NullPointerException.class, () -> new StopControl.Commit(idle, null, null));
  }

  /**
   * 最长的允许 external id（128 字符）必须生成一个仍能容纳在 256 字符 closeRequestId 上限 之内的 durable composite
   * key，并端到端贯穿一次真实 Stop 事务。
   */
  @Test
  void longestExternalIdStillFitsTheTurnEndCloseRequestIdCeiling() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        HarnessRuntimeTestSupport.seedModel(store, ModelInvocationStatus.READY);
    String longestId = "s".repeat(128);
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), longestId, 0));
    assertEquals(StopResult.Status.STOPPED, result.status());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(result.thread().headEntryId()));
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals("STOP/" + baseline.threadId() + "/" + longestId, end.closeRequestId());
    assertTrue(end.closeRequestId().length() <= 256);
  }

  @Test
  void constructorsExposeControlOnlyAndFullProcessorWiring() throws Exception {
    assertTrue(
        Modifier.isPublic(
            HarnessRuntime.class
                .getDeclaredConstructor(HarnessStore.class, Clock.class)
                .getModifiers()));
    assertTrue(
        Modifier.isPublic(
            HarnessRuntime.class
                .getDeclaredConstructor(
                    HarnessStore.class, Clock.class, ModelProcessor.class, ToolProcessor.class)
                .getModifiers()));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessRuntime(
                store,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                (ModelProcessor) null,
                (ToolProcessor) null));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessRuntime(
                store,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                (LongConsumer) null,
                ignored -> {}));
  }
}

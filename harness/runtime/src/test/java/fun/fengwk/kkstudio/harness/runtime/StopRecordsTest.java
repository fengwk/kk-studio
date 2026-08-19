package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.MATERIALIZATION_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertStopped;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.UUID;
import java.util.function.Consumer;

/** StopCommand / StopResult record 校验：每一种被拒绝的形态都精确一致；并通过真实 Stop 验证 STOPPED TURN_END 持久化幂等键。 */
class StopRecordsTest {

  private static final ThreadState THREAD =
      new ThreadState(
          id(1L),
          id(2L),
          id(3L),
          MATERIALIZATION_HASH,
          false,
          1,
          0,
          Instant.ofEpochMilli(1),
          Instant.ofEpochMilli(1));

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(Instant.ofEpochMilli(3_000), ZoneOffset.UTC));
  }

  @Test
  void stopCommandRejectsInvalidShapes() {
    assertThrows(NullPointerException.class, () -> new StopCommand(null, id(1L), 0));
    assertThrows(NullPointerException.class, () -> new StopCommand(id(1L), null, 0));
    assertThrows(IllegalArgumentException.class, () -> new StopCommand(id(1L), id(1L), -1));
  }

  @Test
  void stopCommandAccessorsRoundTrip() {
    StopCommand command = new StopCommand(id(7L), id(8L), 42);
    assertEquals(id(7L), command.threadId());
    assertEquals(id(8L), command.stopRequestId());
    assertEquals(42L, command.expectedRevision());
  }

  @Test
  void stopResultRejectsInvalidShapes() {
    assertThrows(NullPointerException.class, () -> new StopResult(true, null, null, 0, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new StopResult(false, THREAD, null, -1, List.of()));
    assertThrows(NullPointerException.class, () -> new StopResult(false, THREAD, null, 0, null));
  }

  @Test
  void stopResultValidShapesRoundTrip() {
    StopResult idle = new StopResult(false, THREAD, null, 0, List.of());
    assertFalse(idle.replayed());
    assertEquals(THREAD, idle.thread());
    assertNull(idle.stoppedTurnEndEntryId());
    assertEquals(0, idle.cancelledCommandCount());

    StopResult stopped = new StopResult(false, THREAD, id(3L), 2, List.of());
    assertFalse(stopped.replayed());
    assertEquals(id(3L), stopped.stoppedTurnEndEntryId());
    assertEquals(2, stopped.cancelledCommandCount());

    // live receipt replay：replayed=true 且 stoppedTurnEndEntryId 非 null（queued-only replay 则可为
    // null）。
    StopResult replayed = new StopResult(true, THREAD, id(3L), 0, List.of());
    assertTrue(replayed.replayed());
    assertEquals(id(3L), replayed.stoppedTurnEndEntryId());
  }

  @Test
  void stopCommitRejectsInvalidLocalCancellationMetadata() {
    StopResult idle = new StopResult(false, THREAD, null, 0, List.of());
    // Model 与 Tool 执行不能同时取消。
    assertThrows(
        IllegalArgumentException.class,
        () -> new StopControl.Commit(idle, id(1L), List.of(id(2L))));
    assertThrows(NullPointerException.class, () -> new StopControl.Commit(null, null, List.of()));
    assertThrows(NullPointerException.class, () -> new StopControl.Commit(idle, null, null));
  }

  /** stopRequestId 作为 closeRequestId 原样持久化到 STOPPED TURN_END，并端到端贯穿一次真实 Stop 事务。 */
  @Test
  void stopRequestIdIsPersistedAsTheTurnEndCloseRequestId() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        HarnessRuntimeTestSupport.seedModel(store, ModelInvocationStatus.READY);
    UUID stopRequestId = id(8L);
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), stopRequestId, 0));
    assertStopped(result);
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(result.thread().headEntryId()));
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals(stopRequestId, end.closeRequestId());
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
                (Consumer<UUID>) null,
                ignored -> {}));
  }
}

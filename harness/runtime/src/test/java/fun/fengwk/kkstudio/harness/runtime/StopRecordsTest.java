package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertStopped;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.targetReceipt;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** StopCommand / StopResult record 校验：每一种被拒绝的形态都精确一致；并通过真实 Stop 验证 STOPPED TURN_END 持久化幂等键。 */
class StopRecordsTest {

  private static final ThreadState THREAD =
      new ThreadState(
          id(1L),
          id(2L),
          null,
          id(3L),
          CREATION_REQUEST_HASH,
          "main",
          false,
          ThreadExecutionControl.RUNNABLE,
          0,
          1,
          0,
          Instant.ofEpochMilli(1),
          Instant.ofEpochMilli(1));

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime =
        HarnessRuntimeTestSupport.runtime(
            store, Clock.fixed(Instant.ofEpochMilli(3_000), ZoneOffset.UTC));
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
    assertEquals(42L, command.expectedVersion());
  }

  @Test
  void stopResultRejectsInvalidShapes() {
    assertThrows(NullPointerException.class, () -> new StopResult(true, null, List.of()));
    assertThrows(NullPointerException.class, () -> new StopResult(false, THREAD, null));
  }

  @Test
  void stopResultValidShapesRoundTrip() {
    StoppedThreadReceipt receipt =
        new StoppedThreadReceipt(THREAD.id(), id(4L), id(3L), 2, List.of());
    StopResult stopped = new StopResult(false, THREAD, List.of(receipt));
    assertFalse(stopped.replayed());
    assertEquals(THREAD, stopped.thread());
    assertEquals(id(3L), targetReceipt(stopped).stoppedTurnEndEntryId());
    assertEquals(2, targetReceipt(stopped).cancelledCommandCount());
    assertEquals(1, stopped.stoppedThreads().size());

    // replay：replayed=true 且返回持久保存的同一集合，thread 仍是请求目标的权威投影。
    StopResult replayed = new StopResult(true, THREAD, List.of(receipt));
    assertTrue(replayed.replayed());
    assertEquals(id(3L), targetReceipt(replayed).stoppedTurnEndEntryId());
  }

  @Test
  void stopCommitCarriesEveryLocalCancellation() {
    StopResult result =
        new StopResult(
            false,
            THREAD,
            List.of(new StoppedThreadReceipt(THREAD.id(), id(4L), id(3L), 0, List.of())));
    // Commit 同时承载任意数量的 Model / Tool 本地取消（父与多子混合执行），不做单类型截断。
    StopControl.Commit commit = new StopControl.Commit(result, List.of(id(1L)), List.of(id(2L)));
    assertEquals(List.of(id(1L)), commit.modelExecutionIds());
    assertEquals(List.of(id(2L)), commit.toolExecutionIds());
    assertThrows(NullPointerException.class, () -> new StopControl.Commit(null, null, List.of()));
    assertThrows(NullPointerException.class, () -> new StopControl.Commit(result, null, null));
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
  void constructorsRequireCompleteControlDependenciesAndExposeOptionalExecutionWiring()
      throws Exception {
    // HarnessRuntime 的 public wiring surface 必须精确保持为 control-only 与 execution-wired 两种。
    assertEquals(
        2L,
        Arrays.stream(HarnessRuntime.class.getDeclaredConstructors())
            .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
            .count());
    assertTrue(
        Modifier.isPublic(
            HarnessRuntime.class
                .getDeclaredConstructor(
                    HarnessStore.class,
                    Clock.class,
                    TurnResolver.class,
                    CompactionConfigProvider.class)
                .getModifiers()));
    assertTrue(
        Modifier.isPublic(
            HarnessRuntime.class
                .getDeclaredConstructor(
                    HarnessStore.class,
                    Clock.class,
                    TurnResolver.class,
                    CompactionConfigProvider.class,
                    ToolResultHistoryMaterializer.class,
                    ModelProcessor.class,
                    ToolProcessor.class)
                .getModifiers()));
    assertThrows(
        NoSuchMethodException.class,
        () -> HarnessRuntime.class.getDeclaredConstructor(HarnessStore.class, Clock.class));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessRuntime(
                store,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                null,
                HarnessRuntimeTestSupport.DEFAULT_COMPACTION_PROVIDER));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessRuntime(
                store,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                HarnessRuntimeTestSupport.UNUSED_RESOLVER,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessRuntime(
                store,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                HarnessRuntimeTestSupport.UNUSED_RESOLVER,
                HarnessRuntimeTestSupport.DEFAULT_COMPACTION_PROVIDER,
                null,
                (ModelProcessor) null,
                (ToolProcessor) null));
    assertThrows(
        NullPointerException.class,
        () ->
            new HarnessRuntime(
                store,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                HarnessRuntimeTestSupport.UNUSED_RESOLVER,
                HarnessRuntimeTestSupport.DEFAULT_COMPACTION_PROVIDER,
                null,
                (Consumer<UUID>) null,
                ignored -> {}));
  }
}

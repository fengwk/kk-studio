package fun.fengwk.kkstudio.harness.builtin.runtime;

import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.assertStopped;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.modelError;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.seedModelWork;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.seedRunningContinuationModel;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.targetReceipt;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinAgentDriver;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.spi.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.spi.driver.DriverStopResult;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Stop 在 GENERATION_ACTIVE 上：按各状态返回 CANCELLED 并保持 attempt 语义，写入 checkpoint barrier
 * （ASSISTANT_ABORTED thinking-then-text 或 ASSISTANT_ERROR(CANCELLED)），写入一条带复合 key 的 STOPPED
 * TURN_END，删除 THREAD+MODEL Work，且 GENERATION_TERMINAL_PENDING 保持零变更。
 */
class HarnessRuntimeStopModelTest {

  private InMemoryBuiltinHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryBuiltinHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void readyModelStopWritesErrorBarrierKeepsAttemptAndCancelsCommands() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.READY);
    seedQueuedCommand(store, baseline.threadId(), 1L, userMessagePayload("hi"), TestIds.id(1));
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertStopped(result);
    assertEquals(1, targetReceipt(result).cancelledCommandCount());
    // Model row 在 stopModel 同一事务内被物理删除；改用 Entry path 验证 barrier 与 STOPPED TURN_END。
    assertTrue(
        store
            .transaction(tx -> builtin(store, tx).findModelInvocation(baseline.modelId()))
            .isEmpty());

    EntryPath path = pathOf(baseline.threadId());
    assertEquals(5, path.entries().size());
    Entry barrier = path.entries().get(3);
    Entry turnEnd = path.entries().get(4);
    assertTrue(barrier.payload() instanceof AssistantErrorPayload);
    AssistantError error = ((AssistantErrorPayload) barrier.payload()).error();
    assertEquals("CANCELLED", error.code());
    assertStoppedEnd(turnEnd, path, barrier, baseline.threadId(), TestIds.id(1));

    ThreadCommand command =
        store.transaction(
            tx ->
                tx.commands()
                    .findByIdempotencyKey(baseline.threadId(), TestIds.id(1))
                    .orElseThrow());
    assertEquals(ThreadCommandState.CANCELLED, command.state());
    assertFalse(
        store
            .transaction(
                tx -> tx.work().find(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isPresent());
    assertFalse(
        store
            .transaction(
                tx -> tx.work().find(new WorkTarget(BuiltinWorkType.MODEL, baseline.modelId())))
            .isPresent());
  }

  @Test
  void runningModelStopAppendsBarrierWithCANCELLEDErrorAndDeletesTheModelRow() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertStopped(result);
    assertTrue(
        store
            .transaction(tx -> builtin(store, tx).findModelInvocation(baseline.modelId()))
            .isEmpty());
    EntryPath path = pathOf(baseline.threadId());
    Entry barrier = path.entries().get(3);
    assertTrue(barrier.payload() instanceof AssistantErrorPayload);
    AssistantError barrierError = ((AssistantErrorPayload) barrier.payload()).error();
    assertEquals(ProviderErrorKind.CANCELLED.name(), barrierError.code());
    assertTrue(path.head().payload() instanceof TurnEndPayload);
    assertEquals(TurnEndOutcome.STOPPED, ((TurnEndPayload) path.head().payload()).outcome());
  }

  @Test
  void activeModelWithoutAnAssistantRejectsNullBarrier() {
    // 真实 Builtin 活动状态加一个不提供 barrier 的 Driver，验证不能把缺失结果当作可复用结果。
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    AgentDriver delegate =
        new BuiltinAgentDriver(store, (threadId, rootSessionId, path, preparation) -> null);
    AgentDriver driver =
        (AgentDriver)
            Proxy.newProxyInstance(
                AgentDriver.class.getClassLoader(),
                new Class<?>[] {AgentDriver.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("stop")) {
                    return new DriverStopResult(null, List.of());
                  }
                  try {
                    return method.invoke(delegate, args);
                  } catch (InvocationTargetException error) {
                    throw error.getCause();
                  }
                });
    HarnessRuntime missingBarrier =
        new HarnessRuntime(
            store, new DriverRegistry(List.of(driver)), () -> CompactionConfig.DEFAULT);
    ThreadState before =
        store.transaction(tx -> tx.threads().find(baseline.threadId()).orElseThrow());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> missingBarrier.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0)));
    assertTrue(error.getMessage().contains("must provide a stop barrier"));
    assertEquals(
        before, store.transaction(tx -> tx.threads().find(baseline.threadId()).orElseThrow()));
    assertTrue(
        store
            .transaction(tx -> builtin(store, tx).findModelInvocation(baseline.modelId()))
            .isPresent());
  }

  @Test
  void checkpointBarrierPreservesThinkingThenTextWithoutTrim() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    inTransaction(
        store,
        tx -> {
          ModelInvocation model =
              builtin(store, tx).lockModelInvocation(baseline.modelId()).orElseThrow();
          builtin(store, tx)
              .updateModelInvocation(
                  model.checkpoint(
                      new StreamCheckpoint(1, 1, "partial text", "  partial thinking  "), T5));
        });
    runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    // Model row 在 stopModel 同一事务内被物理删除；改用 Entry path 校验 barrier 内容。
    assertTrue(
        store
            .transaction(tx -> builtin(store, tx).findModelInvocation(baseline.modelId()))
            .isEmpty());

    EntryPath path = pathOf(baseline.threadId());
    Entry barrier = path.entries().get(3);
    assertTrue(barrier.payload() instanceof AssistantAbortedPayload);
    AssistantAbortedPayload aborted = (AssistantAbortedPayload) barrier.payload();
    List<AgentMessageContent> contents = aborted.message().contents();
    assertEquals(2, contents.size());
    assertTrue(contents.get(0) instanceof ThinkingMessageContent);
    assertEquals("  partial thinking  ", ((ThinkingMessageContent) contents.get(0)).text());
    assertTrue(contents.get(1) instanceof TextMessageContent);
    assertEquals("partial text", ((TextMessageContent) contents.get(1)).text());
  }

  @Test
  void checkpointWithOnlyThinkingOrOnlyTextProducesASingleBlockBarrier() {
    HarnessRuntimeTestSupport.ModelBaseline thinkingOnly =
        seedModel(store, ModelInvocationStatus.RUNNING);
    inTransaction(
        store,
        tx -> {
          ModelInvocation model =
              builtin(store, tx).lockModelInvocation(thinkingOnly.modelId()).orElseThrow();
          builtin(store, tx)
              .updateModelInvocation(
                  model.checkpoint(new StreamCheckpoint(1, 1, "", "only thinking"), T5));
        });
    runtime.stop(new StopCommand(thinkingOnly.threadId(), TestIds.id(1), 0));
    EntryPath thinkingPath = pathOf(thinkingOnly.threadId());
    AssistantAbortedPayload thinkingBarrier =
        (AssistantAbortedPayload) thinkingPath.entries().get(3).payload();
    assertEquals(1, thinkingBarrier.message().contents().size());
    assertTrue(thinkingBarrier.message().contents().get(0) instanceof ThinkingMessageContent);

    HarnessRuntimeTestSupport.ModelBaseline textOnly =
        seedModel(store, ModelInvocationStatus.RUNNING);
    inTransaction(
        store,
        tx -> {
          ModelInvocation model =
              builtin(store, tx).lockModelInvocation(textOnly.modelId()).orElseThrow();
          builtin(store, tx)
              .updateModelInvocation(
                  model.checkpoint(new StreamCheckpoint(1, 1, "only text", ""), T5));
        });
    runtime.stop(new StopCommand(textOnly.threadId(), TestIds.id(1), 0));
    EntryPath textPath = pathOf(textOnly.threadId());
    AssistantAbortedPayload textBarrier =
        (AssistantAbortedPayload) textPath.entries().get(3).payload();
    assertEquals(1, textBarrier.message().contents().size());
    assertTrue(textBarrier.message().contents().get(0) instanceof TextMessageContent);
  }

  /** 真实 CONTINUATION turn 的 Model stop：barrier 直接挂在无 input 的 TURN_START 之下。 */
  @Test
  void continuationTurnModelStopAppendsBarrierUnderTheBareTurnStart() {
    HarnessRuntimeTestSupport.ModelBaseline baseline = seedRunningContinuationModel(store);
    seedModelWork(store, baseline.modelId());
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertStopped(result);
    EntryPath path = pathOf(baseline.threadId());
    assertEquals(4, path.entries().size());
    Entry barrier = path.entries().get(2);
    assertTrue(barrier.payload() instanceof AssistantErrorPayload);
    assertEquals(baseline.turnStartEntryId(), barrier.parentEntryId());
    assertFalse(
        store
            .transaction(
                tx -> tx.work().find(new WorkTarget(BuiltinWorkType.MODEL, baseline.modelId())))
            .isPresent());
  }

  @Test
  void driverFailureRollsBackSettledToolChildren() {
    // Driver 在工具收敛后失败时，不能留下部分历史或删除的子行。
    var baseline = HarnessRuntimeTestSupport.seedToolBaseline(store, 1);
    UUID tool = baseline.toolIds().getFirst();
    HarnessRuntimeTestSupport.seedToolWork(store, tool);
    AgentDriver delegate =
        new BuiltinAgentDriver(store, (threadId, rootSessionId, path, preparation) -> null);
    AgentDriver failing =
        (AgentDriver)
            Proxy.newProxyInstance(
                AgentDriver.class.getClassLoader(),
                new Class<?>[] {AgentDriver.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("stop")) {
                    throw new IllegalStateException("driver failure");
                  }
                  try {
                    return method.invoke(delegate, args);
                  } catch (InvocationTargetException error) {
                    throw error.getCause();
                  }
                });
    HarnessRuntime failingRuntime =
        new HarnessRuntime(
            store, new DriverRegistry(List.of(failing)), () -> CompactionConfig.DEFAULT);
    ThreadState before =
        store.transaction(tx -> tx.threads().find(baseline.threadId()).orElseThrow());
    List<Entry> beforeEntries =
        store.transaction(tx -> tx.entries().loadBySession(baseline.sessionId()));
    assertThrows(
        IllegalStateException.class,
        () ->
            failingRuntime.stop(
                new StopCommand(baseline.threadId(), TestIds.id(1), before.version())));
    assertEquals(
        before, store.transaction(tx -> tx.threads().find(baseline.threadId()).orElseThrow()));
    assertEquals(
        beforeEntries, store.transaction(tx -> tx.entries().loadBySession(baseline.sessionId())));
    assertTrue(store.transaction(tx -> tx.tools().find(tool)).isPresent());
    assertTrue(
        store
            .transaction(tx -> builtin(store, tx).findModelInvocation(baseline.modelId()))
            .isPresent());
    assertTrue(
        store
            .transaction(tx -> tx.work().find(new WorkTarget(WorkTargetType.TOOL, tool)))
            .isPresent());
  }

  @Test
  void stopSettlesActiveToolsBeforeDeletingTheirModel() {
    // 真正附着 Model 的批次：在途副作用未知，而未执行的 sibling 被取消。
    var baseline = HarnessRuntimeTestSupport.seedToolBaseline(store, 2);
    UUID ready = baseline.toolIds().get(0);
    UUID running = baseline.toolIds().get(1);
    HarnessRuntimeTestSupport.approveTool(store, running);
    HarnessRuntimeTestSupport.markRunningTool(store, running);
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    HarnessRuntimeTestSupport.seedToolWork(store, ready);
    HarnessRuntimeTestSupport.seedToolWork(store, running);

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));
    assertStopped(result);
    assertEquals(0, targetReceipt(result).cancelledCommandCount());
    List<ToolResultStatus> statuses =
        pathOf(baseline.threadId()).entries().stream()
            .filter(e -> e.payload() instanceof MessagePayload m && m.toolResultMetadata() != null)
            .map(e -> ((MessagePayload) e.payload()).toolResultMetadata().status())
            .toList();
    assertEquals(List.of(ToolResultStatus.CANCELLED, ToolResultStatus.UNKNOWN), statuses);
    assertTrue(store.transaction(tx -> tx.tools().find(ready)).isEmpty());
    assertTrue(store.transaction(tx -> tx.tools().find(running)).isEmpty());
    assertTrue(
        store
            .transaction(tx -> builtin(store, tx).findModelInvocation(baseline.modelId()))
            .isEmpty());
    for (WorkTarget target :
        List.of(
            new WorkTarget(WorkTargetType.THREAD, baseline.threadId()),
            new WorkTarget(BuiltinWorkType.MODEL, baseline.modelId()),
            new WorkTarget(WorkTargetType.TOOL, ready),
            new WorkTarget(WorkTargetType.TOOL, running))) {
      assertTrue(store.transaction(tx -> tx.work().find(target)).isEmpty());
    }
  }

  @Test
  void stopMaterializesPendingToolCallWithoutDispatchingIt() {
    // A committed response is not active work, but Stop must still settle its tool batch.
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        store.transaction(
            tx -> {
              UUID sessionId = tx.nextId();
              UUID rootId = tx.nextId();
              UUID turnId = tx.nextId();
              UUID threadId = tx.nextId();
              UUID userId = tx.nextId();
              UUID modelId = tx.nextId();
              tx.sessions().insert(HarnessRuntimeTestSupport.session(sessionId));
              tx.entries().insert(HarnessRuntimeTestSupport.rootEntry(rootId, sessionId));
              tx.entries()
                  .insert(
                      HarnessRuntimeTestSupport.turnStartEntry(
                          turnId, sessionId, rootId, T5, threadId));
              tx.entries()
                  .insert(
                      HarnessRuntimeTestSupport.userMessageEntry(userId, sessionId, turnId, T5));
              tx.threads().insert(HarnessRuntimeTestSupport.thread(threadId, sessionId, userId));
              ModelInvocation model =
                  HarnessRuntimeTestSupport.modelInvocationWithRequest(
                      modelId,
                      threadId,
                      turnId,
                      userId,
                      HarnessRuntimeTestSupport.tooledModelRequest(List.of("bash")),
                      T5);
              builtin(store, tx).insertModelInvocation(model);
              ModelInvocation running = model.markRunning(T5);
              builtin(store, tx).updateModelInvocation(running);
              builtin(store, tx)
                  .updateModelInvocation(
                      running.succeed(
                          HarnessRuntimeTestSupport.responseWithToolCalls("call-1"), T5));
              return new HarnessRuntimeTestSupport.ModelBaseline(
                  sessionId, rootId, turnId, threadId, modelId);
            });

    assertEquals(
        ThreadLiveState.DRIVER_RESPONSE_PENDING,
        runtime.getThreadSnapshot(baseline.threadId()).liveState());
    assertFalse(runtime.getThreadSnapshot(baseline.threadId()).executionFacts().hasWork());
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertStopped(result);
    assertEquals(
        ThreadExecutionControl.STOPPED,
        store
            .transaction(tx -> tx.threads().find(baseline.threadId()).orElseThrow())
            .executionControl());
    EntryPath path = pathOf(baseline.threadId());
    Entry assistant =
        path.entries().stream()
            .filter(
                e ->
                    e.payload() instanceof MessagePayload message
                        && message.message().contents().stream()
                            .anyMatch(c -> c instanceof ToolCallMessageContent))
            .findFirst()
            .orElseThrow();
    ToolCallMessageContent call =
        (ToolCallMessageContent)
            ((MessagePayload) assistant.payload())
                .message().contents().stream()
                    .filter(c -> c instanceof ToolCallMessageContent)
                    .findFirst()
                    .orElseThrow();
    assertEquals("call-1", call.toolCallId());
    assertTrue(store.transaction(tx -> tx.tools().loadByAssistantEntry(assistant.id())).isEmpty());
    assertTrue(
        store
            .transaction(tx -> builtin(store, tx).findModelInvocation(baseline.modelId()))
            .isEmpty());
    assertTrue(
        store
            .transaction(
                tx -> tx.work().find(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isEmpty());
    assertTrue(
        store
            .transaction(
                tx -> tx.work().find(new WorkTarget(BuiltinWorkType.MODEL, baseline.modelId())))
            .isEmpty());
    assertTrue(
        store
            .transaction(
                tx -> tx.work().claimNext(WorkTargetType.TOOL, "test", Duration.ofMinutes(1)))
            .isEmpty());
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.STOPPED, end.outcome());
    assertEquals(TurnEndReason.USER_STOP, end.reason());
    assertEquals(TestIds.id(1), end.closeRequestId());
  }

  @Test
  void terminalPendingModelStopMaterializesCommittedResultThenStops() {
    // 真实形态：ROOT -> TURN_START(INPUT) -> USER；Model 已 terminal（CANCELLED）但结果尚未 apply。
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    inTransaction(
        store,
        tx -> {
          ModelInvocation model =
              builtin(store, tx).lockModelInvocation(baseline.modelId()).orElseThrow();
          builtin(store, tx).updateModelInvocation(model.cancel(modelError(), T5));
        });
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());

    // 提交先于 Stop：Stop 不 409，而是把已提交的 terminal 结果物化进历史后正常停止。
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertStopped(result);
    // Model 行在物化/停止后物理删除。
    assertTrue(
        store
            .transaction(tx -> builtin(store, tx).findModelInvocation(baseline.modelId()))
            .isEmpty());
    EntryPath path = pathOf(baseline.threadId());
    // 已提交结果被保留：ASSISTANT_ERROR(CANCELLED) 与 FAILED TURN_END 在 STOP 边界之前。
    assertTrue(path.entries().stream().anyMatch(e -> e.payload() instanceof AssistantErrorPayload));
    assertTrue(
        path.entries().stream()
            .anyMatch(
                e ->
                    e.payload() instanceof TurnEndPayload end
                        && end.outcome() == TurnEndOutcome.FAILED));
    TurnEndPayload stopEnd = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.STOPPED, stopEnd.outcome());
    assertNull(
        store
            .transaction(tx -> builtin(store, tx).findModelInvocation(baseline.modelId()))
            .orElse(null));
  }

  /** 已提交的 terminal Model 携带超大上游错误正文时，Stop 的历史物化必须完整落库并正常停止。 */
  @Test
  void terminalPendingModelWithLargeUpstreamErrorMaterializesThenStops() {
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    String message = "HTTP 404\n\n<!DOCTYPE html>\n" + "not found ".repeat(600) + "错误\n</html>\n  ";
    inTransaction(
        store,
        tx -> {
          ModelInvocation model =
              builtin(store, tx).lockModelInvocation(baseline.modelId()).orElseThrow();
          builtin(store, tx)
              .updateModelInvocation(
                  model.fail(
                      new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, message), T5));
        });
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));

    assertStopped(result);
    assertTrue(
        store
            .transaction(tx -> builtin(store, tx).findModelInvocation(baseline.modelId()))
            .isEmpty());
    EntryPath path = pathOf(baseline.threadId());
    // 完整上游错误正文被原样物化为 ASSISTANT_ERROR，而不是被截断或导致 Stop 抛异常。
    AssistantErrorPayload materialized =
        path.entries().stream()
            .filter(e -> e.payload() instanceof AssistantErrorPayload)
            .map(e -> (AssistantErrorPayload) e.payload())
            .findFirst()
            .orElseThrow();
    assertEquals("INVALID_REQUEST", materialized.error().code());
    assertEquals(message, materialized.error().message());
    assertEquals(TurnEndOutcome.STOPPED, ((TurnEndPayload) path.head().payload()).outcome());
  }

  private ModelInvocation storedModel(UUID modelId) {
    return store.transaction(tx -> builtin(store, tx).findModelInvocation(modelId).orElseThrow());
  }

  private EntryPath pathOf(UUID threadId) {
    ThreadState thread = store.transaction(tx -> tx.threads().lock(threadId).orElseThrow());
    return store.transaction(tx -> tx.entries().loadPath(thread.headEntryId()));
  }

  private static void assertStoppedEnd(
      Entry turnEnd, EntryPath path, Entry barrier, UUID threadId, UUID stopRequestId) {
    assertTrue(turnEnd.payload() instanceof TurnEndPayload);
    TurnEndPayload end = (TurnEndPayload) turnEnd.payload();
    assertEquals(TurnEndOutcome.STOPPED, end.outcome());
    assertEquals(TurnEndReason.USER_STOP, end.reason());
    assertEquals(stopRequestId, end.closeRequestId());
    assertEquals(path.entries().get(1).id(), end.turnStartEntryId());
    assertEquals(barrier.id(), turnEnd.parentEntryId());
    assertEquals(turnEnd.id(), path.head().id());
  }
}

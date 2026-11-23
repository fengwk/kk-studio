package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ToolDispatch#dispatchReadyTools} 的直接行为：同一文件键只派发第一个仍 live 的调用，其余保持等待；不同键与无键调用同时派发。
 *
 * <p>断言只看可观察结果：返回的 dispatched 标志与事务内 TOOL Work 行，不触碰 ToolInvocation 状态。
 */
class ToolDispatchTest {

  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ExecutionSelection("fake", "{}"), null);
  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final String HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final int CONTEXT_WINDOW = 100_000;

  /** 测试意图：同键 sibling 只有 callIndex 最小的 live 调用派发，后者必须等前序结束。 */
  @Test
  void onlyTheFirstLiveCallOfTheSameFileKeyIsDispatched() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Batch batch = seed(store, ToolSpec.edit("/repo/a.txt"), ToolSpec.edit("/repo/a.txt"));

    Dispatch dispatch = dispatch(store, batch);

    assertTrue(dispatch.dispatched());
    assertEquals(List.of(0), dispatch.callIndexes());
  }

  /** 测试意图：不同文件键互不互斥，无键调用进入即派发，一次调用可同时派发多条 Work。 */
  @Test
  void differentKeysAndUnkeyedCallsAreDispatchedTogether() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Batch batch =
        seed(store, ToolSpec.edit("/repo/a.txt"), ToolSpec.edit("/repo/b.txt"), ToolSpec.bash());

    Dispatch dispatch = dispatch(store, batch);

    assertTrue(dispatch.dispatched());
    assertEquals(List.of(0, 1, 2), dispatch.callIndexes());
  }

  /** 测试意图：同键第一个调用仍在等审批时后序调用不得越过它，整个键保持未派发。 */
  @Test
  void approvalWaitingHeadKeepsLaterSameKeyCallsWaiting() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Batch batch = seed(store, ToolSpec.edit("/repo/a.txt"), ToolSpec.edit("/repo/a.txt"));
    requestApproval(store, batch, batch.toolIds().get(0));

    Dispatch dispatch = dispatch(store, batch);

    assertFalse(dispatch.dispatched());
    assertEquals(List.of(), dispatch.callIndexes());
  }

  /** 测试意图：同键第一个调用已进入 RUNNING（attempt&gt;0）时同样占住键位，后序 READY 调用保持等待。 */
  @Test
  void runningHeadKeepsLaterSameKeyCallsWaiting() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Batch batch = seed(store, ToolSpec.edit("/repo/a.txt"), ToolSpec.edit("/repo/a.txt"));
    startRunning(store, batch, batch.toolIds().get(0));

    Dispatch dispatch = dispatch(store, batch);

    assertFalse(dispatch.dispatched());
    assertEquals(List.of(), dispatch.callIndexes());
  }

  /** 测试意图：同键第一个调用已进入 terminal 时键位让给下一个 live 调用，文件变更因此继续串行推进。 */
  @Test
  void terminalHeadReleasesTheKeyForTheNextSameKeyCall() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Batch batch = seed(store, ToolSpec.edit("/repo/a.txt"), ToolSpec.edit("/repo/a.txt"));
    cancel(store, batch, batch.toolIds().get(0));

    Dispatch dispatch = dispatch(store, batch);

    assertTrue(dispatch.dispatched());
    assertEquals(List.of(1), dispatch.callIndexes());
  }

  /** 测试意图：已带 preflight approval 的 READY 头不是派发候选，同键后序调用继续等待。 */
  @Test
  void readyHeadCarryingPreflightApprovalIsNotDispatched() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Batch batch = seed(store, ToolSpec.edit("/repo/a.txt"), ToolSpec.edit("/repo/a.txt"));
    approve(store, batch, batch.toolIds().get(0));

    Dispatch dispatch = dispatch(store, batch);

    assertFalse(dispatch.dispatched());
    assertEquals(List.of(), dispatch.callIndexes());
  }

  /** 测试意图：已有 TOOL Work 的键不再重复请求，重复派发不推进 wakeVersion。 */
  @Test
  void alreadyRequestedFileKeyIsNotRequestedTwice() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Batch batch = seed(store, ToolSpec.edit("/repo/a.txt"), ToolSpec.edit("/repo/a.txt"));
    assertEquals(List.of(0), dispatch(store, batch).callIndexes());

    Dispatch repeated = dispatch(store, batch);

    assertFalse(repeated.dispatched());
    assertEquals(List.of(0), repeated.callIndexes());
    assertEquals(1L, repeated.works().get(0).wakeVersion());
  }

  private record Dispatch(
      boolean dispatched, List<Integer> callIndexes, Map<Integer, Work> works) {}

  /** 文件工具按 ROUTED 冻结当前 Environment，无键工具（bash）只在环境上执行。 */
  private record ToolSpec(String toolName, String argumentsJson) {

    static ToolSpec edit(String path) {
      return new ToolSpec("edit", "{\"path\":\"" + path + "\"}");
    }

    static ToolSpec bash() {
      return new ToolSpec("bash", "{}");
    }

    boolean keyed() {
      return "edit".equals(toolName) || "write".equals(toolName);
    }
  }

  private record Batch(UUID threadId, UUID assistantEntryId, List<UUID> toolIds) {}

  private static Dispatch dispatch(InMemoryHarnessStore store, Batch batch) {
    return store.transaction(
        tx -> {
          tx.threads().lock(batch.threadId()).orElseThrow();
          List<ToolInvocation> siblings = tx.tools().loadByAssistantEntry(batch.assistantEntryId());
          boolean dispatched = ToolDispatch.dispatchReadyTools(tx, siblings.reversed());
          List<Integer> callIndexes = new ArrayList<>();
          Map<Integer, Work> works = new HashMap<>();
          for (ToolInvocation invocation : siblings) {
            tx.work()
                .find(new WorkTarget(WorkTargetType.TOOL, invocation.id()))
                .ifPresent(
                    work -> {
                      callIndexes.add(invocation.callIndex());
                      works.put(invocation.callIndex(), work);
                    });
          }
          return new Dispatch(dispatched, List.copyOf(callIndexes), Map.copyOf(works));
        });
  }

  private static Batch seed(InMemoryHarnessStore store, ToolSpec... specs) {
    store.setNow(NOW);
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, "session-" + sessionId, NOW));
          tx.entries()
              .insert(new Entry(rootEntryId, sessionId, null, new RootPayload(SETTINGS), NOW));
          tx.entries()
              .insert(
                  new Entry(
                      turnStartEntryId,
                      sessionId,
                      rootEntryId,
                      new TurnStartPayload(
                          TurnStartReason.INPUT,
                          SETTINGS,
                          threadId,
                          CONTEXT_WINDOW,
                          16_384,
                          null,
                          null),
                      NOW));
          ThreadState thread =
              new ThreadState(
                  threadId,
                  sessionId,
                  null,
                  turnStartEntryId,
                  HASH,
                  "main",
                  threadId,
                  false,
                  ThreadKind.USER,
                  ThreadConstraints.NONE,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  NOW,
                  NOW);
          tx.threads().insert(thread);
          UUID userEntryId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      userEntryId,
                      sessionId,
                      turnStartEntryId,
                      new MessagePayload(
                          new AgentMessage(
                              AgentMessageRole.USER, List.of(new TextMessageContent("run tools"))),
                          null,
                          null),
                      NOW));
          UUID assistantEntryId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      assistantEntryId, sessionId, userEntryId, assistantPayload(specs), NOW));
          tx.threads().update(thread.advanceHead(assistantEntryId, NOW));
          List<UUID> toolIds = new ArrayList<>();
          List<ToolInvocation> invocations = new ArrayList<>();
          for (int i = 0; i < specs.length; i++) {
            UUID toolId = tx.nextId();
            toolIds.add(toolId);
            invocations.add(
                invocation(toolId, threadId, turnStartEntryId, assistantEntryId, i, specs[i]));
          }
          tx.tools().insert(invocations);
          return new Batch(threadId, assistantEntryId, List.copyOf(toolIds));
        });
  }

  private static MessagePayload assistantPayload(ToolSpec[] specs) {
    List<AgentMessageContent> contents = new ArrayList<>();
    contents.add(new TextMessageContent("dispatching tools"));
    for (int i = 0; i < specs.length; i++) {
      contents.add(
          new ToolCallMessageContent(
              callId(i), specs[i].toolName(), specs[i].toolName(), specs[i].argumentsJson()));
    }
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(
            GenerationStopReason.COMPLETE, new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L)),
        null);
  }

  private static ToolInvocation invocation(
      UUID toolId,
      UUID threadId,
      UUID turnStartEntryId,
      UUID assistantEntryId,
      int callIndex,
      ToolSpec spec) {
    return new ToolInvocation(
        toolId,
        threadId,
        turnStartEntryId,
        assistantEntryId,
        callIndex,
        new ToolCall(callId(callIndex), spec.toolName(), spec.argumentsJson()),
        binding(spec),
        ToolInvocationStatus.READY,
        0,
        null,
        null,
        null,
        NOW,
        NOW);
  }

  private static ToolBinding binding(ToolSpec spec) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            spec.toolName(),
            "description of " + spec.toolName(),
            spec.toolName(),
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofSeconds(30));
    AgentToolDefinition definition = new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE);
    ContributorBinding contributor = new ContributorBinding("core", spec.toolName());
    return spec.keyed()
        ? new ToolBinding(definition, contributor, EnvironmentSupport.ROUTED, ENV, "dev")
        : new ToolBinding(definition, contributor, EnvironmentSupport.REQUIRED, ENV, "dev");
  }

  private static String callId(int callIndex) {
    return "call-" + callIndex;
  }

  private static void requestApproval(InMemoryHarnessStore store, Batch batch, UUID toolId) {
    store.transaction(
        tx -> {
          tx.threads().lock(batch.threadId()).orElseThrow();
          ToolInvocation tool = tx.tools().lock(toolId).orElseThrow();
          tx.tools().update(List.of(tool.requestApproval("needs approval", NOW)));
          return null;
        });
  }

  private static void approve(InMemoryHarnessStore store, Batch batch, UUID toolId) {
    store.transaction(
        tx -> {
          tx.threads().lock(batch.threadId()).orElseThrow();
          ToolInvocation tool = tx.tools().lock(toolId).orElseThrow();
          tx.tools().update(List.of(tool.markApprovalNotRequired(NOW)));
          return null;
        });
  }

  private static void startRunning(InMemoryHarnessStore store, Batch batch, UUID toolId) {
    store.transaction(
        tx -> {
          tx.threads().lock(batch.threadId()).orElseThrow();
          ToolInvocation tool = tx.tools().lock(toolId).orElseThrow();
          ToolInvocation preflighted = tool.markApprovalNotRequired(NOW);
          tx.tools().update(List.of(preflighted));
          tx.tools().update(List.of(preflighted.markRunning(NOW)));
          return null;
        });
  }

  private static void cancel(InMemoryHarnessStore store, Batch batch, UUID toolId) {
    store.transaction(
        tx -> {
          tx.threads().lock(batch.threadId()).orElseThrow();
          ToolInvocation tool = tx.tools().lock(toolId).orElseThrow();
          tx.tools()
              .update(List.of(tool.cancel(new ToolInvocationError("STOPPED", "cancelled"), NOW)));
          return null;
        });
  }
}

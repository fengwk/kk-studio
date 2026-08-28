package fun.fengwk.kkstudio.harness.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SubagentContext;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.testing.TestThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** task 平台工具对模型公开的 descriptor 契约、执行入口拒绝语义与完整观察/取消生命周期。 */
class TaskToolTest {
  private static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);

  private static final int DEFAULT_MAX_TURNS = 7;
  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
  private static final UUID PARENT_THREAD_ID = new UUID(0L, 11L);
  private static final UUID ROOT_THREAD_ID = new UUID(0L, 9L);
  private static final UUID TASK_INVOCATION_ID = new UUID(0L, 22L);
  private static final UUID CHILD_THREAD_ID = new UUID(0L, 33L);
  private static final UUID CHILD_ROOT_ENTRY_ID = new UUID(0L, 31L);
  private static final UUID RESUME_THREAD_ID = new UUID(0L, 40L);
  private static final UUID PARENT_SESSION_ID = new UUID(0L, 10L);
  private static final UUID SESSION_ID = new UUID(0L, 30L);

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static final SubagentContext SUBAGENT_CONTEXT =
      new SubagentContext(PARENT_THREAD_ID, PARENT_THREAD_ID, TASK_INVOCATION_ID, 2);
  private static final ModelUsage USAGE = new ModelUsage(0, 0, 0, 0, 0, 0, 0);
  private static final ModelCost COST =
      new ModelCost(
          "USD",
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);

  private ExecutorService executor;
  private HarnessRuntime runtime;
  private SubagentBranchSettingsMaterializer settingsMaterializer;
  private TestThreadChangeSource changeSource;
  private TaskTool tool;
  private int callId;

  @BeforeEach
  void setUp() {
    executor = Executors.newVirtualThreadPerTaskExecutor();
    settingsMaterializer = mock(SubagentBranchSettingsMaterializer.class);
    changeSource = new TestThreadChangeSource();
    tool = tool(config(2, 10, Duration.ZERO, DEFAULT_MAX_TURNS));
  }

  @AfterEach
  void tearDown() {
    executor.close();
  }

  private static SubagentConfig config(
      int maxDepth, int maxConcurrency, Duration idleTimeout, int maxTurns) {
    return new SubagentConfig(maxDepth, maxConcurrency, 0, idleTimeout, maxTurns);
  }

  private TaskTool tool(SubagentConfig cfg) {
    return tool(cfg, new SubagentRunRegistry());
  }

  private TaskTool tool(SubagentConfigProvider provider) {
    return tool(provider, new SubagentRunRegistry());
  }

  private TaskTool tool(SubagentConfig cfg, SubagentRunRegistry registry) {
    return tool(() -> cfg, registry);
  }

  private TaskTool tool(SubagentConfigProvider provider, SubagentRunRegistry registry) {
    return new TaskTool(
        () -> runtime,
        settingsMaterializer,
        provider,
        registry,
        changeSource,
        executor,
        new ObjectMapper());
  }

  /** descriptor 固定暴露 name/version/renderer/sideEffect/no-timeout 与必填 schema。 */
  @Test
  void exposesCanonicalDescriptorContract() {
    var descriptor = tool.descriptor();

    assertEquals(TaskTool.NAME, descriptor.name());
    assertEquals(TaskTool.VERSION, descriptor.version());
    assertEquals(TaskTool.RENDERER_KEY, descriptor.rendererKey());
    assertEquals(ToolSideEffect.NON_IDEMPOTENT, descriptor.sideEffect());
    assertEquals(Duration.ZERO, descriptor.timeout());
    assertFalse(descriptor.description().isBlank());
    assertFalse(descriptor.description().contains("Parameters:"), descriptor.description());

    ToolParamsSchema schema = descriptor.inputSchema();
    assertTrue(
        schema
            .properties()
            .get("maxTurns")
            .description()
            .contains("Defaults to the current subagent policy when omitted"),
        schema.properties().get("maxTurns").description());
    assertFalse(
        schema
            .properties()
            .get("maxTurns")
            .description()
            .contains("Defaults to " + DEFAULT_MAX_TURNS),
        schema.properties().get("maxTurns").description());
    assertFalse(schema.description().isBlank());
    assertEquals(
        Set.of("subagent_type", "prompt", "maxTurns", "session_id"), schema.properties().keySet());
    assertInstanceOf(ToolStringSchema.class, schema.properties().get("subagent_type"));
    assertInstanceOf(ToolStringSchema.class, schema.properties().get("prompt"));
    assertInstanceOf(ToolIntegerSchema.class, schema.properties().get("maxTurns"));
    assertInstanceOf(ToolStringSchema.class, schema.properties().get("session_id"));
    assertEquals(Set.of("subagent_type", "prompt"), schema.required());
    assertFalse(schema.additionalProperties());
  }

  /** descriptor 不读取 live policy；配置变化前后返回同一个冻结值，避免 equality 随运行期设置失效。 */
  @Test
  void keepsDescriptorStableAcrossPolicyChangesWithoutReadingProvider() {
    AtomicReference<SubagentConfig> liveConfig =
        new AtomicReference<>(config(2, 10, Duration.ZERO, DEFAULT_MAX_TURNS));
    AtomicInteger providerReads = new AtomicInteger();
    TaskTool configuredTool =
        tool(
            () -> {
              providerReads.incrementAndGet();
              return liveConfig.get();
            });

    var before = configuredTool.descriptor();
    liveConfig.set(config(2, 10, Duration.ZERO, 13));
    var after = configuredTool.descriptor();

    assertEquals(before, after);
    assertSame(before, after);
    assertEquals(0, providerReads.get());
  }

  /** 缺失 durable context 的执行请求在提交前同步拒绝，listener 不被触碰。 */
  @Test
  void synchronouslyRejectsMissingDurableContext() {
    ToolExecutionListener listener = mock(ToolExecutionListener.class);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall(
                "call-1", TaskTool.NAME, "{\"subagent_type\":\"researcher\",\"prompt\":\"do it\"}"),
            Duration.ZERO);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> tool.execute(request, listener));
    assertEquals("task requires durable ToolExecutionContext", error.getMessage());
    verifyNoInteractions(listener);
  }

  /** Executor 在 TaskExecution 启动前拒绝时返回既有容量错误，且不得创建 durable 子 Thread。 */
  @Test
  void executorRejectionReturnsDeterministicErrorWithoutCreatingChild() throws Exception {
    runtime = mock(HarnessRuntime.class);
    ExecutorService rejectingExecutor = Executors.newSingleThreadExecutor();
    rejectingExecutor.shutdown();
    TaskTool rejectingTool =
        new TaskTool(
            () -> runtime,
            settingsMaterializer,
            () -> config(2, 10, Duration.ZERO, DEFAULT_MAX_TURNS),
            new SubagentRunRegistry(),
            changeSource,
            rejectingExecutor,
            new ObjectMapper());
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle =
        rejectingTool.execute(
            request(
                "call-capacity",
                "{\"subagent_type\":\"researcher\",\"prompt\":\"must not spawn\"}"),
            listener);
    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);

    // 固定 executor 的第 N+1 次拒绝发生在 TaskExecution.run 前，因此没有 NEW_SESSION/Thread durable 写入，
    // 但模型仍收到 task 自己既有的 ERROR envelope，而不是 HOST Tool UNKNOWN。
    assertTrue(result.error(), result.toString());
    assertFalse(handle.isCancelled());
    assertTrue(
        ((TextToolContent) result.contents().getFirst())
            .text()
            .contains("Subagent execution capacity is exhausted"),
        result.toString());
    verifyNoInteractions(runtime);
  }

  /** 参数语义非法（通过 schema 但被 parseArguments 拒绝）时，异步以错误 ToolResult 完成。 */
  @Test
  void completesRejectedArgumentsAsynchronously() throws Exception {
    RecordingListener listener = new RecordingListener();
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall(
                "call-2",
                TaskTool.NAME,
                "{\"subagent_type\":\" researcher \",\"prompt\":\"do it\",\"session_id\":\"abc\"}"),
            Duration.ZERO,
            new ToolExecutionContext(id(11), id(22)));

    ToolExecutionHandle handle = tool.execute(request, listener);

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(result.error(), result.toString());
    assertFalse(handle.isCancelled());
    String text = ((TextToolContent) result.contents().get(0)).text();
    assertTrue(text.contains("subagent_type must not contain surrounding whitespace"), text);
    assertEquals(1, listener.completedCalls.get());
  }

  /** 完整 create-child 路径验证冻结 allowlist、ROOT 归属、command 入队、状态 partial 与最终报告。 */
  @Test
  void createsDurableChildAndReturnsCompletedReport() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings parentSettings = settings("parent", "parent-model");
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(parentSettings, List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    String report = "x".repeat(8_100);
    ThreadSnapshot terminal = completedChildSnapshot(child, report);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubAcceptNewSession(runtime, child);
    stubChildSnapshots(runtime, child, terminal);
    when(settingsMaterializer.materialize(eq("reviewer"), eq(null))).thenReturn(childSettings);
    RecordingListener listener = new RecordingListener();
    ToolExecutionRequest request =
        request("call-create", "{\"subagent_type\":\"reviewer\",\"prompt\":\"Review the change\"}");

    tool.execute(request, listener);

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertFalse(result.error(), result.toString());
    String text = ((TextToolContent) result.contents().getFirst()).text();
    assertTrue(text.startsWith("<task id=\"" + CHILD_THREAD_ID + "\" state=\"completed\">"), text);
    assertTrue(text.contains("... (truncated)"), text);
    assertFalse(text.contains("x".repeat(8_001)), "completed report must be bounded");
    assertEquals(
        "task.result", new ObjectMapper().readTree(result.detailsJson()).get("kind").asText());
    assertEquals(
        CHILD_THREAD_ID.toString(),
        new ObjectMapper().readTree(result.detailsJson()).get("threadId").asText());

    ArgumentCaptor<AcceptCommandsCommand> acceptCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(runtime).acceptCommands(acceptCaptor.capture(), any(AcceptancePreflight.class));
    AcceptCommandsCommand accept = acceptCaptor.getValue();
    AcceptCommandsTarget.NewSession target = (AcceptCommandsTarget.NewSession) accept.target();
    assertEquals(childSettings, target.rootSettings());
    assertTrue(target.yoloEnabled());
    assertEquals(
        new SubagentContext(PARENT_THREAD_ID, PARENT_THREAD_ID, TASK_INVOCATION_ID, 2),
        target.subagentContext());
    assertEquals(1, accept.commands().size());
    assertNotNull(accept.commands().getFirst().clientCommandId());
    UserMessageCommandPayload prompt =
        assertInstanceOf(UserMessageCommandPayload.class, accept.commands().getFirst().payload());
    assertEquals(AgentMessage.user("Review the change"), prompt.message());

    assertFalse(listener.partials.isEmpty());
    ToolResult latestStatus = listener.partials.getLast();
    String statusText = ((TextToolContent) latestStatus.contents().getFirst()).text();
    var status = new ObjectMapper().readTree(statusText);
    assertEquals("task.status", status.get("kind").asText());
    assertEquals(CHILD_THREAD_ID.toString(), status.get("threadId").asText());
    assertEquals(1, status.get("turns").asInt());
    assertEquals(
        "task.status",
        new ObjectMapper().readTree(latestStatus.detailsJson()).get("kind").asText());
  }

  /** 执行期只认父 ModelInvocation 冻结的 allowlist，不能依据后续 catalog 扩权。 */
  @Test
  void rejectsSubagentOutsideFrozenParentAllowlist() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"),
            List.of(
                new SubagentBinding("reviewer", "Review"),
                new SubagentBinding("researcher", "Research")));
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    RecordingListener listener = new RecordingListener();

    tool.execute(
        request("call-denied", "{\"subagent_type\":\"ghost\",\"prompt\":\"Do research\"}"),
        listener);

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(result.error(), result.toString());
    assertTrue(
        ((TextToolContent) result.contents().getFirst())
            .text()
            .contains("subagent_type \"ghost\" is not allowed; available: reviewer / researcher"));
    verify(runtime, never()).acceptCommands(any(), any());
    verifyNoInteractions(settingsMaterializer);
  }

  /** execute 入口对 null 参数快速失败。 */
  @Test
  void rejectsNullExecuteArguments() {
    ToolExecutionListener listener = mock(ToolExecutionListener.class);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall(
                "call-3", TaskTool.NAME, "{\"subagent_type\":\"researcher\",\"prompt\":\"do it\"}"),
            Duration.ZERO,
            new ToolExecutionContext(id(1), id(1)));

    assertThrows(NullPointerException.class, () -> tool.execute(null, listener));
    assertThrows(NullPointerException.class, () -> tool.execute(request, null));
  }

  /** 恢复 quiescent 子 Session：完整 settings diff 与 prompt 一次入队，完成后以原 thread 返回报告。 */
  @Test
  void resumesQuiescentChildSessionWithFullSettingsDiff() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings base = settings("alpha", "model-a");
    BranchSettings target = settings("beta", "model-b");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot resumed = quiescentResumeSnapshot(base, SUBAGENT_CONTEXT, false, id(34), 5L);
    ThreadSnapshot terminal = resumeTerminalSnapshot(resumed, "resumed and finished");
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubSnapshots(runtime, RESUME_THREAD_ID, resumed, resumed, terminal);
    when(settingsMaterializer.materialize(eq("reviewer"), isNull())).thenReturn(target);
    RecordingListener listener = new RecordingListener();

    tool.execute(
        request(
            "call-resume",
            "{\"subagent_type\":\"reviewer\",\"prompt\":\"Continue the work\",\"session_id\":\""
                + RESUME_THREAD_ID
                + "\"}"),
        listener);

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertFalse(result.error(), result.toString());
    String text = ((TextToolContent) result.contents().getFirst()).text();
    assertTrue(text.startsWith("<task id=\"" + RESUME_THREAD_ID + "\" state=\"completed\">"), text);
    assertTrue(text.contains("resumed and finished"), text);

    ArgumentCaptor<AcceptCommandsCommand> acceptCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(runtime).acceptCommands(acceptCaptor.capture(), any(AcceptancePreflight.class));
    AcceptCommandsCommand accept = acceptCaptor.getValue();
    AcceptCommandsTarget.Thread threadTarget = (AcceptCommandsTarget.Thread) accept.target();
    assertEquals(RESUME_THREAD_ID, threadTarget.threadId());
    assertEquals(id(34), threadTarget.expectedHeadEntryId());
    assertEquals(2L, threadTarget.expectedNextCommandSequence());
    assertEquals(3, accept.commands().size());
    assertNotNull(accept.commands().get(0).clientCommandId());
    assertEquals(
        "beta",
        assertInstanceOf(SetAgentCommandPayload.class, accept.commands().get(0).payload())
            .agentName());
    assertEquals(
        new ModelSelection("provider", "model-b", "default"),
        assertInstanceOf(SetModelCommandPayload.class, accept.commands().get(1).payload()).model());
    UserMessageCommandPayload prompt =
        assertInstanceOf(UserMessageCommandPayload.class, accept.commands().get(2).payload());
    assertEquals(AgentMessage.user("Continue the work"), prompt.message());
    assertEquals(1, listener.completedCalls.get());
    verify(settingsMaterializer, times(1)).materialize(eq("reviewer"), isNull());
  }

  /** 恢复时 session 不存在、归属不符或未 quiescent 都以错误 ToolResult 拒绝。 */
  @Test
  void rejectsResumeOfMissingForeignOrBusySession() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings settings = settings("parent", "parent-model");
    ThreadSnapshot parent =
        parentSnapshot(settings, List.of(new SubagentBinding("reviewer", "Review")));
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);

    when(runtime.getThreadSnapshot(RESUME_THREAD_ID))
        .thenThrow(new HarnessRuntimeNotFoundException("gone"));
    RecordingListener missing = new RecordingListener();
    tool.execute(
        request(
            "call-1",
            "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\",\"session_id\":\""
                + RESUME_THREAD_ID
                + "\"}"),
        missing);
    ToolResult missingResult = missing.completed.get(5, TimeUnit.SECONDS);
    assertTrue(missingResult.error(), missingResult.toString());
    assertTrue(
        ((TextToolContent) missingResult.contents().getFirst())
            .text()
            .contains("subagent session \"" + RESUME_THREAD_ID + "\" was not found"),
        missingResult.toString());

    // 归属错误：subagentContext.parentThreadId 与当前 parent 不一致。
    ThreadSnapshot foreign =
        quiescentResumeSnapshot(
            settings, new SubagentContext(id(99), PARENT_THREAD_ID, id(55), 2), false, id(34), 1L);
    doReturn(foreign).when(runtime).getThreadSnapshot(RESUME_THREAD_ID);
    RecordingListener foreignListener = new RecordingListener();
    tool.execute(
        request(
            "call-2",
            "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\",\"session_id\":\""
                + RESUME_THREAD_ID
                + "\"}"),
        foreignListener);
    ToolResult foreignResult = foreignListener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(foreignResult.error(), foreignResult.toString());
    assertTrue(
        ((TextToolContent) foreignResult.contents().getFirst())
            .text()
            .contains("does not belong to this parent"),
        foreignResult.toString());

    // 非 quiescent：Model 仍活跃。
    ThreadSnapshot busy = quiescentResumeSnapshot(settings, SUBAGENT_CONTEXT, false, id(34), 1L);
    ThreadSnapshot busyWithModel =
        new ThreadSnapshot(
            busy.thread(),
            busy.entryPath(),
            busy.queuedCommands(),
            modelInvocation(id(1)),
            busy.toolSiblings(),
            List.of());
    doReturn(busyWithModel).when(runtime).getThreadSnapshot(RESUME_THREAD_ID);
    RecordingListener busyListener = new RecordingListener();
    tool.execute(
        request(
            "call-3",
            "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\",\"session_id\":\""
                + RESUME_THREAD_ID
                + "\"}"),
        busyListener);
    ToolResult busyResult = busyListener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(busyResult.error(), busyResult.toString());
    assertTrue(
        ((TextToolContent) busyResult.contents().getFirst())
            .text()
            .contains("subagent session \"" + RESUME_THREAD_ID + "\" is not quiescent"),
        busyResult.toString());

    // 非 quiescent：head 仍是 continueModel=true 的 open turn。
    ThreadSnapshot continuing =
        quiescentResumeSnapshot(settings, SUBAGENT_CONTEXT, true, id(34), 1L);
    doReturn(continuing).when(runtime).getThreadSnapshot(RESUME_THREAD_ID);
    RecordingListener continuingListener = new RecordingListener();
    tool.execute(
        request(
            "call-4",
            "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\",\"session_id\":\""
                + RESUME_THREAD_ID
                + "\"}"),
        continuingListener);
    ToolResult continuingResult = continuingListener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(continuingResult.error(), continuingResult.toString());
    assertTrue(
        ((TextToolContent) continuingResult.contents().getFirst())
            .text()
            .contains("is not quiescent"),
        continuingResult.toString());
  }

  /** 委派深度上限、空 allowlist 与 invocation 脱离父 Thread 都以错误完成。 */
  @Test
  void rejectsDelegationBeyondDepthEmptyAllowlistOrDetachedInvocation() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings settings = settings("parent", "parent-model");
    ThreadSnapshot deepParent =
        parentSnapshot(
            new SubagentContext(id(7), ROOT_THREAD_ID, id(1), 2),
            settings,
            List.of(new SubagentBinding("reviewer", "Review")),
            List.of(taskInvocationSibling()));
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(deepParent);
    RecordingListener depthListener = new RecordingListener();
    tool.execute(
        request("call-depth", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), depthListener);
    ToolResult depthResult = depthListener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(depthResult.error(), depthResult.toString());
    assertTrue(
        ((TextToolContent) depthResult.contents().getFirst())
            .text()
            .contains("subagent max depth reached: 2/2"),
        depthResult.toString());

    ThreadSnapshot noSubagents = parentSnapshot(settings, List.of());
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(noSubagents);
    RecordingListener allowlistListener = new RecordingListener();
    tool.execute(
        request("call-allowlist", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"),
        allowlistListener);
    ToolResult allowlistResult = allowlistListener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(allowlistResult.error(), allowlistResult.toString());
    assertTrue(
        ((TextToolContent) allowlistResult.contents().getFirst())
            .text()
            .contains("the frozen parent invocation does not allow subagent delegation"),
        allowlistResult.toString());

    ThreadSnapshot detached =
        parentSnapshot(
            null, settings, List.of(new SubagentBinding("reviewer", "Review")), List.of());
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(detached);
    RecordingListener detachedListener = new RecordingListener();
    tool.execute(
        request("call-detached", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"),
        detachedListener);
    ToolResult detachedResult = detachedListener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(detachedResult.error(), detachedResult.toString());
    assertTrue(
        ((TextToolContent) detachedResult.contents().getFirst())
            .text()
            .contains("task invocation is no longer attached to its parent Thread context"),
        detachedResult.toString());

    // Model 已结束（null）的 parent 同样视为 invocation 脱离上下文。
    ThreadSnapshot noModel =
        new ThreadSnapshot(
            detached.thread(),
            detached.entryPath(),
            detached.queuedCommands(),
            null,
            detached.toolSiblings(),
            List.of());
    doReturn(noModel).when(runtime).getThreadSnapshot(PARENT_THREAD_ID);
    RecordingListener noModelListener = new RecordingListener();
    tool.execute(
        request("call-no-model", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"),
        noModelListener);
    ToolResult noModelResult = noModelListener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(noModelResult.error(), noModelResult.toString());
    assertTrue(
        ((TextToolContent) noModelResult.contents().getFirst())
            .text()
            .contains("task invocation is no longer attached to its parent Thread context"),
        noModelResult.toString());
  }

  /** 嵌套委派把 rootThreadId 与 depth 传播给子 Session，状态报告携带真实 depth。 */
  @Test
  void propagatesRootThreadAndDepthForNestedDelegation() throws Exception {
    TaskTool nestedTool = tool(config(3, 10, Duration.ZERO, DEFAULT_MAX_TURNS));
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings parentSettings = settings("parent", "parent-model");
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            new SubagentContext(id(7), ROOT_THREAD_ID, id(1), 2),
            parentSettings,
            List.of(new SubagentBinding("reviewer", "Review")),
            List.of(taskInvocationSibling()));
    ThreadSnapshot child =
        childRootSnapshot(
            childSettings,
            new SubagentContext(PARENT_THREAD_ID, ROOT_THREAD_ID, TASK_INVOCATION_ID, 3));
    ThreadSnapshot terminal = completedChildSnapshot(child, "nested report");
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubAcceptNewSession(runtime, child);
    stubChildSnapshots(runtime, child, terminal);
    when(settingsMaterializer.materialize(eq("reviewer"), isNull())).thenReturn(childSettings);
    RecordingListener listener = new RecordingListener();

    nestedTool.execute(
        request("call-nested", "{\"subagent_type\":\"reviewer\",\"prompt\":\"Go deeper\"}"),
        listener);

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertFalse(result.error(), result.toString());
    assertTrue(
        ((TextToolContent) result.contents().getFirst()).text().contains("nested report"),
        result.toString());

    ArgumentCaptor<AcceptCommandsCommand> acceptCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(runtime).acceptCommands(acceptCaptor.capture(), any(AcceptancePreflight.class));
    AcceptCommandsTarget.NewSession target =
        (AcceptCommandsTarget.NewSession) acceptCaptor.getValue().target();
    assertEquals(
        new SubagentContext(PARENT_THREAD_ID, ROOT_THREAD_ID, TASK_INVOCATION_ID, 3),
        target.subagentContext());

    ToolResult status = listener.partials.getFirst();
    var statusJson = new ObjectMapper().readTree(status.detailsJson());
    assertEquals(3, statusJson.get("depth").asInt());
  }

  /** cancel 在子 Session 启动前生效：不创建 Thread、不触碰 runtime，直接以 cancelled 完成。 */
  @Test
  void cancelsBeforeChildSessionStarts() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings settings = settings("parent", "parent-model");
    ThreadSnapshot parent =
        parentSnapshot(settings, List.of(new SubagentBinding("reviewer", "Review")));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID))
        .thenAnswer(
            inv -> {
              entered.countDown();
              release.await();
              return parent;
            });
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle =
        tool.execute(
            request("call-pre", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    handle.cancel();
    // 重复 cancel 是幂等 no-op。
    handle.cancel();
    release.countDown();

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(result.error(), result.toString());
    String text = ((TextToolContent) result.contents().getFirst()).text();
    assertTrue(text.contains("Cancelled before the subagent session started."), text);
    assertEquals(
        "cancelled", new ObjectMapper().readTree(result.detailsJson()).get("state").asText());
    assertTrue(handle.isCancelled());
    verify(runtime, never()).acceptCommands(any(), any());
    verify(runtime, never()).stop(any());
  }

  /** cancel 在观察循环中生效：best-effort stop 子 Thread 后以 cancelled 完成，保留 session。 */
  @Test
  void stopsChildAndCompletesCancelledOnUserCancel() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot running =
        runningRootSnapshot(
            CHILD_THREAD_ID, CHILD_ROOT_ENTRY_ID, 1L, List.of(), List.of(queuedCommand()));
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubAcceptNewSession(runtime, child);
    when(settingsMaterializer.materialize(any(), isNull())).thenReturn(childSettings);
    AtomicInteger reads = new AtomicInteger();
    CountDownLatch observed = new CountDownLatch(1);
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenAnswer(
            inv -> {
              int n = reads.incrementAndGet();
              if (n == 1) {
                return child;
              }
              if (n == 2) {
                observed.countDown();
              }
              return running;
            });
    // cancel 的唯一 stop 序列遇意外失败被吞掉；观察循环随后仍会完成 cancelled，且 single-owner 不再重复 stop。
    AtomicInteger stops = new AtomicInteger();
    doAnswer(
            inv -> {
              if (stops.incrementAndGet() == 1) {
                throw new IllegalStateException("stop failed");
              }
              return null;
            })
        .when(runtime)
        .stop(any(StopCommand.class));
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle =
        tool.execute(
            request("call-stop", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);
    assertTrue(observed.await(5, TimeUnit.SECONDS));
    handle.cancel();
    // 重复 cancel 是幂等 no-op。
    handle.cancel();

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(result.error(), result.toString());
    String text = ((TextToolContent) result.contents().getFirst()).text();
    assertTrue(
        text.contains("Cancelled by user. Session preserved as `" + CHILD_THREAD_ID + "`"), text);
    assertEquals(
        "cancelled", new ObjectMapper().readTree(result.detailsJson()).get("state").asText());
    assertTrue(handle.isCancelled());

    ArgumentCaptor<StopCommand> stopCaptor = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime, times(1)).stop(stopCaptor.capture());
    assertEquals(CHILD_THREAD_ID, stopCaptor.getValue().threadId());
    assertNotNull(stopCaptor.getValue().stopRequestId());
    assertEquals(1L, stopCaptor.getValue().expectedVersion());
    assertEquals(1, listener.completedCalls.get());
    // cancel 主动 signal 唤醒观察循环，完成路径释放全部订阅。
    assertEquals(
        0, changeSource.activeSubscriptions(CHILD_THREAD_ID), "cancel must release subscriptions");
    assertEquals(1, changeSource.totalClosed());
  }

  /** single-owner：cancel() 与观察循环竞争时只执行一条 3-attempt stop 重试序列；连续冲突耗尽后仍以 cancelled 完成。 */
  @Test
  void retriesCancelOnStaleVersionAndExhaustsRetries() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot runningRev1 =
        runningRootSnapshot(CHILD_THREAD_ID, CHILD_ROOT_ENTRY_ID, 1L, List.of(), List.of());
    ThreadSnapshot runningRev2 =
        runningRootSnapshot(CHILD_THREAD_ID, CHILD_ROOT_ENTRY_ID, 2L, List.of(), List.of());
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubAcceptNewSession(runtime, child);
    when(settingsMaterializer.materialize(any(), isNull())).thenReturn(childSettings);
    AtomicInteger reads = new AtomicInteger();
    CountDownLatch observed = new CountDownLatch(1);
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenAnswer(
            inv -> {
              int n = reads.incrementAndGet();
              if (n == 1) {
                return child;
              }
              if (n == 2) {
                observed.countDown();
                return runningRev1;
              }
              if (n == 3) {
                return runningRev1;
              }
              return runningRev2;
            });
    // 唯一 stop 序列（3 attempts）每次 stop 都遇 stale version，重读后重试直至耗尽。
    AtomicInteger stops = new AtomicInteger();
    doAnswer(
            inv -> {
              stops.incrementAndGet();
              throw new HarnessRuntimeConflictException(
                  HarnessRuntimeConflictException.Reason.STALE_VERSION, "stale version");
            })
        .when(runtime)
        .stop(any(StopCommand.class));
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle =
        tool.execute(
            request("call-retry", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);
    assertTrue(observed.await(5, TimeUnit.SECONDS));
    handle.cancel();

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(result.error(), result.toString());
    assertTrue(
        ((TextToolContent) result.contents().getFirst())
            .text()
            .contains("Session preserved as `" + CHILD_THREAD_ID + "`"),
        result.toString());

    // 无论 cancel() 还是观察循环赢得执行权，都只有一条 3-attempt 序列：3 次 stop、reads 3/4/5 连续。
    ArgumentCaptor<StopCommand> stopCaptor = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime, times(3)).stop(stopCaptor.capture());
    assertEquals(1L, stopCaptor.getAllValues().get(0).expectedVersion());
    for (int i = 1; i < 3; i++) {
      assertEquals(2L, stopCaptor.getAllValues().get(i).expectedVersion());
    }
  }

  /** 并发 cancel/观察循环竞争取消时，至多一条 3-attempt stop 序列执行：再次 cancel 不产生重复副作用。 */
  @Test
  void concurrentCancelAndObserverExecuteSingleStopSequence() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot runningRev1 =
        runningRootSnapshot(CHILD_THREAD_ID, CHILD_ROOT_ENTRY_ID, 1L, List.of(), List.of());
    ThreadSnapshot runningRev2 =
        runningRootSnapshot(CHILD_THREAD_ID, CHILD_ROOT_ENTRY_ID, 2L, List.of(), List.of());
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubAcceptNewSession(runtime, child);
    when(settingsMaterializer.materialize(any(), isNull())).thenReturn(childSettings);
    AtomicInteger reads = new AtomicInteger();
    CountDownLatch observed = new CountDownLatch(1);
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenAnswer(
            inv -> {
              int n = reads.incrementAndGet();
              if (n == 1) {
                return child;
              }
              if (n == 2) {
                observed.countDown();
                return runningRev1;
              }
              if (n == 3) {
                return runningRev1;
              }
              return runningRev2;
            });
    CountDownLatch stopStarted = new CountDownLatch(1);
    CountDownLatch releaseStop = new CountDownLatch(1);
    AtomicInteger stops = new AtomicInteger();
    doAnswer(
            inv -> {
              if (stops.incrementAndGet() == 1) {
                stopStarted.countDown();
                try {
                  assertTrue(releaseStop.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrupted);
                }
              }
              throw new HarnessRuntimeConflictException(
                  HarnessRuntimeConflictException.Reason.STALE_VERSION, "stale version");
            })
        .when(runtime)
        .stop(any(StopCommand.class));
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle =
        tool.execute(
            request("call-concurrent", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"),
            listener);
    assertTrue(observed.await(5, TimeUnit.SECONDS));
    // 独立线程触发 cancel，与观察循环竞争 single-owner 执行权。
    Thread canceller = Thread.ofVirtual().start(handle::cancel);
    // 唯一 stop 序列已开始（无论由 cancel 线程还是观察循环执行）。
    assertTrue(stopStarted.await(5, TimeUnit.SECONDS), "stop sequence must start");
    // 序列已持有执行权后再次 cancel：cancelled CAS 失败，不产生第二条 stop 序列。
    handle.cancel();
    releaseStop.countDown();
    canceller.join();

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(result.error(), result.toString());
    assertTrue(
        ((TextToolContent) result.contents().getFirst())
            .text()
            .contains("Session preserved as `" + CHILD_THREAD_ID + "`"),
        result.toString());

    ArgumentCaptor<StopCommand> stopCaptor = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime, times(3)).stop(stopCaptor.capture());
    assertEquals(1L, stopCaptor.getAllValues().get(0).expectedVersion());
    for (int i = 1; i < 3; i++) {
      assertEquals(2L, stopCaptor.getAllValues().get(i).expectedVersion());
    }
    assertEquals(1, listener.completedCalls.get(), "must complete exactly once");
  }

  /** 观察线程被中断时以 listener.onError 上报，不伪造 ToolResult。 */
  @Test
  void failsWhenObservationThreadIsInterrupted() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot running =
        runningRootSnapshot(CHILD_THREAD_ID, CHILD_ROOT_ENTRY_ID, 1L, List.of(), List.of());
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubAcceptNewSession(runtime, child);
    when(settingsMaterializer.materialize(any(), isNull())).thenReturn(childSettings);
    AtomicReference<Thread> observationThread = new AtomicReference<>();
    CountDownLatch observed = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenAnswer(
            inv -> {
              int n = reads.incrementAndGet();
              if (n == 1) {
                return child;
              }
              if (n == 2) {
                observationThread.set(Thread.currentThread());
                observed.countDown();
              }
              return running;
            });
    RecordingListener listener = new RecordingListener();

    tool.execute(
        request("call-interrupt", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);
    assertTrue(observed.await(5, TimeUnit.SECONDS));
    observationThread.get().interrupt();

    ExecutionException error =
        assertThrows(ExecutionException.class, () -> listener.completed.get(5, TimeUnit.SECONDS));
    assertInstanceOf(IllegalStateException.class, error.getCause());
    assertEquals("task observation thread was interrupted", error.getCause().getMessage());
    // 中断路径同样释放订阅，不遗留注册。
    assertEquals(0, changeSource.activeSubscriptions(CHILD_THREAD_ID));
    assertEquals(1, changeSource.totalClosed());
  }

  /** cancel 时子 Thread 已消失：cancelChild best-effort 静默返回，仍以 cancelled 完成。 */
  @Test
  void ignoresMissingChildWhileCancelling() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot running =
        runningRootSnapshot(CHILD_THREAD_ID, CHILD_ROOT_ENTRY_ID, 1L, List.of(), List.of());
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubAcceptNewSession(runtime, child);
    when(settingsMaterializer.materialize(any(), isNull())).thenReturn(childSettings);
    AtomicInteger reads = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenAnswer(
            inv -> {
              if (reads.incrementAndGet() == 1) {
                entered.countDown();
                release.await();
                return child;
              }
              throw new HarnessRuntimeNotFoundException("thread gone");
            });
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle =
        tool.execute(
            request("call-gone", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    handle.cancel();
    release.countDown();

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(result.error(), result.toString());
    assertTrue(
        ((TextToolContent) result.contents().getFirst())
            .text()
            .contains("Session preserved as `" + CHILD_THREAD_ID + "`"),
        result.toString());
    verify(runtime, never()).stop(any());
  }

  /** cancel 在子 Thread attach 之后、观察循环之前生效：创建已发生但立即 cascade stop。 */
  @Test
  void cancelsAfterChildAttachBeforeAwait() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot running =
        runningRootSnapshot(CHILD_THREAD_ID, CHILD_ROOT_ENTRY_ID, 1L, List.of(), List.of());
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubAcceptNewSession(runtime, child);
    when(settingsMaterializer.materialize(any(), isNull())).thenReturn(childSettings);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenAnswer(
            inv -> {
              if (reads.incrementAndGet() == 1) {
                entered.countDown();
                release.await();
                return child;
              }
              return running;
            });
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle =
        tool.execute(
            request("call-attach", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    handle.cancel();
    release.countDown();

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(result.error(), result.toString());
    assertTrue(
        ((TextToolContent) result.contents().getFirst())
            .text()
            .contains("Session preserved as `" + CHILD_THREAD_ID + "`"),
        result.toString());
    ArgumentCaptor<StopCommand> stopCaptor = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime, times(1)).stop(stopCaptor.capture());
    assertEquals(CHILD_THREAD_ID, stopCaptor.getValue().threadId());
    assertNotNull(stopCaptor.getValue().stopRequestId());
    assertEquals(1L, stopCaptor.getValue().expectedVersion());
    assertEquals(1, listener.completedCalls.get());
  }

  /** idle timeout 以 durable fingerprint 进展重置，active tool 期间不计时，超时后停止并保留 session。 */
  @Test
  void timesOutIdleChildAndPreservesSession() throws Exception {
    SubagentConfig idleConfig = config(3, 10, Duration.ofMillis(150), DEFAULT_MAX_TURNS);
    SubagentRunRegistry registry = new SubagentRunRegistry();
    TaskTool idleTool = tool(idleConfig, registry);
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot withTool =
        runningRootSnapshot(
            CHILD_THREAD_ID,
            CHILD_ROOT_ENTRY_ID,
            1L,
            List.of(toolInvocation(id(51), ToolApproval.notRequired())),
            List.of());
    ThreadSnapshot idle =
        runningRootSnapshot(CHILD_THREAD_ID, CHILD_ROOT_ENTRY_ID, 2L, List.of(), List.of());
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubAcceptNewSession(runtime, child);
    when(settingsMaterializer.materialize(any(), isNull())).thenReturn(childSettings);
    AtomicInteger reads = new AtomicInteger();
    CountDownLatch initialRead = new CountDownLatch(1);
    CountDownLatch idleRead = new CountDownLatch(1);
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenAnswer(
            inv -> {
              int n = reads.incrementAndGet();
              if (n == 1) {
                return child; // createChild
              }
              if (n == 2) {
                initialRead.countDown();
                return withTool; // await 首读：active tool，idle 不计时
              }
              idleRead.countDown();
              return idle; // 首次观察到 quiescent（rev2），idle 时钟从这里开始
            });
    try (SubagentRunRegistry.Reservation nested =
        registry.reserve(CHILD_THREAD_ID, PARENT_THREAD_ID, null, idleConfig)) {
      nested.attach(id(44));
      AtomicInteger relaySequence = new AtomicInteger();
      Thread relayUpdater =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      while (!Thread.currentThread().isInterrupted()) {
                        int sequence = relaySequence.incrementAndGet();
                        registry.publishStatus(
                            new SubagentRunRegistry.RelayedStatus(
                                id(44),
                                "coder",
                                "running_model",
                                3,
                                sequence,
                                0,
                                "running_model",
                                List.of()));
                        Thread.sleep(10);
                      }
                    } catch (InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                    }
                  });
      try {
        RecordingListener listener = new RecordingListener();

        idleTool.execute(
            request("call-idle", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);

        assertTrue(initialRead.await(5, TimeUnit.SECONDS), "await must reach withTool state");
        // 推进到 quiescent：idle 时钟从该次权威读取开始。
        changeSource.signal(CHILD_THREAD_ID);
        assertTrue(idleRead.await(5, TimeUnit.SECONDS), "await must observe the idle state");
        // descendant relay 每 10ms 持续发布也不能重置 durable idle 时钟；超过 150ms 后由 version wake 按权威 snapshot
        // 判超时。
        Thread.sleep(200);
        changeSource.signal(CHILD_THREAD_ID);

        // descendant relay 持续变化也不能重置 durable idle 时钟。
        ToolResult result = listener.completed.get(3, TimeUnit.SECONDS);
        assertTrue(result.error(), result.toString());
        String text = ((TextToolContent) result.contents().getFirst()).text();
        assertTrue(text.contains("Subagent idle timeout after PT0.15S"), text);
        assertTrue(text.contains("Session preserved as `" + CHILD_THREAD_ID + "`"), text);
        assertEquals(
            "error", new ObjectMapper().readTree(result.detailsJson()).get("state").asText());
        ArgumentCaptor<StopCommand> idleStop = ArgumentCaptor.forClass(StopCommand.class);
        verify(runtime).stop(idleStop.capture());
        assertEquals(CHILD_THREAD_ID, idleStop.getValue().threadId());
        assertNotNull(idleStop.getValue().stopRequestId());
        assertEquals(2L, idleStop.getValue().expectedVersion());

        // 观察到的状态序列：active tool 先于 quiescent，因此 idle 计时只在 tool 结束后生效。
        assertEquals(
            "running_tool",
            new ObjectMapper()
                .readTree(listener.partials.get(0).detailsJson())
                .get("state")
                .asText());
        boolean sawQueued = false;
        for (ToolResult partial : listener.partials) {
          String state = new ObjectMapper().readTree(partial.detailsJson()).get("state").asText();
          if ("queued".equals(state)) {
            sawQueued = true;
          }
        }
        assertTrue(sawQueued, "must publish a queued status once the child becomes quiescent");
      } finally {
        relayUpdater.interrupt();
        relayUpdater.join();
      }
    }
  }

  /** 无后续 version 信号时 idle 到期由限时等待本身唤醒：不重读 durable snapshot，按缓存 active-tools 语义触发取消。 */
  @Test
  void timesOutIdleWithoutVersionSignalsAndReadsSnapshotOnce() throws Exception {
    SubagentConfig idleConfig = config(3, 10, Duration.ofMillis(150), DEFAULT_MAX_TURNS);
    SubagentRunRegistry registry = new SubagentRunRegistry();
    TaskTool idleTool = tool(idleConfig, registry);
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot idle =
        runningRootSnapshot(CHILD_THREAD_ID, CHILD_ROOT_ENTRY_ID, 2L, List.of(), List.of());
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubAcceptNewSession(runtime, child);
    when(settingsMaterializer.materialize(any(), isNull())).thenReturn(childSettings);
    AtomicInteger reads = new AtomicInteger();
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenAnswer(
            inv -> {
              int n = reads.incrementAndGet();
              if (n == 1) {
                return child; // createChild
              }
              return idle; // await 首读即 quiescent：idle 时钟从此刻开始，之后无任何 version 信号
            });
    RecordingListener listener = new RecordingListener();

    idleTool.execute(
        request("call-idle-once", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);

    ToolResult result = listener.completed.get(3, TimeUnit.SECONDS);
    assertTrue(result.error(), result.toString());
    String text = ((TextToolContent) result.contents().getFirst()).text();
    assertTrue(text.contains("Subagent idle timeout after PT0.15S"), text);
    assertTrue(text.contains("Session preserved as `" + CHILD_THREAD_ID + "`"), text);
    assertEquals("error", new ObjectMapper().readTree(result.detailsJson()).get("state").asText());
    ArgumentCaptor<StopCommand> idleStop = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime).stop(idleStop.capture());
    assertEquals(CHILD_THREAD_ID, idleStop.getValue().threadId());
    assertNotNull(idleStop.getValue().stopRequestId());
    assertEquals(2L, idleStop.getValue().expectedVersion());
    // 观察循环的权威 snapshot 读取只发生一次（await 首读）；第 3 次读取来自 idle cancel 的 stop 预读（stop 协议必需）。
    // idle 到期本身由限时等待唤醒，不重读 durable snapshot。
    assertEquals(3, reads.get(), "idle timeout must not re-read the durable snapshot");
    assertEquals(
        0,
        changeSource.activeSubscriptions(CHILD_THREAD_ID),
        "idle timeout must release subscriptions");
    assertEquals(1, changeSource.totalClosed());
  }

  /** 省略 maxTurns 时使用执行期最新 policy；达到预算后每 5 轮提醒一次，冲突只跳过本轮。 */
  @Test
  void usesLatestDefaultMaxTurnsForReminders() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    AtomicReference<SubagentConfig> liveConfig =
        new AtomicReference<>(config(2, 10, Duration.ZERO, DEFAULT_MAX_TURNS));
    TaskTool liveTool = tool(liveConfig::get);
    liveTool.descriptor();
    liveConfig.set(config(2, 10, Duration.ZERO, 3));
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ModelInvocation model = modelInvocation(id(1));
    ThreadSnapshot threeTurns = turnCountSnapshot(3, model, 1L);
    ThreadSnapshot eightTurns = turnCountSnapshot(8, model, 2L);
    ThreadSnapshot terminal = completedChildSnapshot(child, "done after turns");
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubChildSnapshots(runtime, child, threeTurns, eightTurns, eightTurns, terminal);
    when(settingsMaterializer.materialize(any(), isNull())).thenReturn(childSettings);
    // NEW_SESSION 接受返回 child；THREAD reminder 接受计数，超过 2 次后抛冲突（被观察循环跳过，不中断本轮）。
    AtomicInteger enqueues = new AtomicInteger();
    when(runtime.acceptCommands(any(AcceptCommandsCommand.class), any(AcceptancePreflight.class)))
        .thenAnswer(
            inv -> {
              AcceptCommandsCommand command = inv.getArgument(0);
              if (command.target() instanceof AcceptCommandsTarget.NewSession) {
                return acceptedNewSession(child, command);
              }
              if (enqueues.incrementAndGet() > 2) {
                throw new HarnessRuntimeConflictException(
                    HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR, "cursor moved");
              }
              return null;
            });
    RecordingListener listener = new RecordingListener();

    liveTool.execute(
        request(liveTool, "call-remind", "{\"subagent_type\":\"reviewer\",\"prompt\":\"Work\"}"),
        listener);

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertFalse(result.error(), result.toString());
    assertTrue(
        ((TextToolContent) result.contents().getFirst()).text().contains("done after turns"),
        result.toString());

    assertEquals(
        3,
        new ObjectMapper().readTree(listener.partials.get(0).detailsJson()).get("turns").asInt());

    ArgumentCaptor<AcceptCommandsCommand> acceptCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(runtime, atLeast(2))
        .acceptCommands(acceptCaptor.capture(), any(AcceptancePreflight.class));
    List<UUID> reminderIds = new ArrayList<>();
    for (AcceptCommandsCommand command : acceptCaptor.getAllValues()) {
      if (command.target() instanceof AcceptCommandsTarget.Thread
          && command.commands().getFirst().payload()
              instanceof CustomMessageCommandPayload reminder) {
        reminderIds.add(command.commands().getFirst().clientCommandId());
        assertEquals(
            SubagentPrompts.maxTurnsReminder(),
            ((TextMessageContent) reminder.message().contents().getFirst()).text());
      }
    }
    // 第 3 轮首次提醒，之后每 5 轮重复（3 -> 8）；后续提醒入队冲突只跳过本轮观察，重试后仍会成功。
    assertTrue(reminderIds.size() >= 2, reminderIds.toString());
    assertTrue(reminderIds.stream().distinct().count() >= 2, reminderIds.toString());
  }

  /** fingerprint 稳定、无任何 version 信号时仍按 1s 心跳基于缓存 snapshot 重发 task.status——绝不重读 durable snapshot。 */
  @Test
  void republishesStatusHeartbeatWhileFingerprintIsStable() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot continueHead = continueModelHeadSnapshot(true, 1L);
    ThreadSnapshot terminal = continueModelHeadSnapshot(false, 1L);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    stubAcceptNewSession(runtime, child);
    when(settingsMaterializer.materialize(any(), isNull())).thenReturn(childSettings);
    long start = System.nanoTime();
    AtomicInteger reads = new AtomicInteger();
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenAnswer(
            inv -> {
              int n = reads.incrementAndGet();
              if (n == 1) {
                return child; // createChild
              }
              return System.nanoTime() - start < Duration.ofMillis(1_100).toNanos()
                  ? continueHead
                  : terminal;
            });
    RecordingListener listener = new RecordingListener();

    tool.execute(
        request(
            "call-heartbeat", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\",\"maxTurns\":1}"),
        listener);
    // terminal 边界没有 version 事件；由测试在 1.1s 主动发一次 wake 触发权威重读，否则观察循环只发心跳。
    Thread signaler =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    Thread.sleep(1_100);
                    changeSource.signal(CHILD_THREAD_ID);
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                });

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    signaler.join();
    assertFalse(result.error(), result.toString());
    assertTrue(listener.partials.size() >= 2, "expected initial publish plus heartbeat republish");
    // createChild + await 首读 + terminal wake 重读 = 3 次；1s 心跳期间无任何 snapshot 读取。
    assertTrue(reads.get() <= 3, "heartbeat must not re-read the durable snapshot: " + reads.get());
    for (ToolResult partial : listener.partials) {
      assertEquals(
          "task.status", new ObjectMapper().readTree(partial.detailsJson()).get("kind").asText());
    }
  }

  /** FAILED/STOPPED/CANCELLED terminal outcome 分别映射 error/cancelled，报告 fallback 兜底。 */
  @Test
  void mapsFailedStoppedAndCancelledTerminalOutcomes() throws Exception {
    // FAILED：AssistantErrorPayload 的报告与 TURN_FAILED 边界。
    HarnessRuntime failedRuntime = mock(HarnessRuntime.class);
    this.runtime = failedRuntime;
    stubCreateFlow(failedRuntime, settings("reviewer", "review-model"));
    stubChildSnapshots(
        failedRuntime,
        childRootSnapshot(settings("reviewer", "review-model")),
        continueModelHeadSnapshot(true, 1L),
        failedTerminalSnapshot());
    RecordingListener failedListener = new RecordingListener();
    tool.execute(
        request("call-failed", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"),
        failedListener);
    ToolResult failed = failedListener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(failed.error(), failed.toString());
    assertTrue(
        ((TextToolContent) failed.contents().getFirst()).text().contains("child exploded"),
        failed.toString());
    assertEquals("error", new ObjectMapper().readTree(failed.detailsJson()).get("state").asText());

    // STOPPED：AssistantAbortedPayload 文本作为报告。
    HarnessRuntime stoppedRuntime = mock(HarnessRuntime.class);
    this.runtime = stoppedRuntime;
    stubCreateFlow(stoppedRuntime, settings("reviewer", "review-model"));
    stubChildSnapshots(
        stoppedRuntime,
        childRootSnapshot(settings("reviewer", "review-model")),
        stoppedTerminalSnapshot());
    RecordingListener stoppedListener = new RecordingListener();
    tool.execute(
        request("call-stopped", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"),
        stoppedListener);
    ToolResult stopped = stoppedListener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(stopped.error(), stopped.toString());
    assertTrue(
        ((TextToolContent) stopped.contents().getFirst()).text().contains("stopped by user"),
        stopped.toString());
    assertEquals(
        "cancelled", new ObjectMapper().readTree(stopped.detailsJson()).get("state").asText());

    // CANCELLED 无文本报告：fallback 文案兜底；null maxTurns/session_id 走默认值。
    HarnessRuntime cancelledRuntime = mock(HarnessRuntime.class);
    this.runtime = cancelledRuntime;
    stubCreateFlow(cancelledRuntime, settings("reviewer", "review-model"));
    stubChildSnapshots(
        cancelledRuntime,
        childRootSnapshot(settings("reviewer", "review-model")),
        cancelledTerminalSnapshot());
    RecordingListener cancelledListener = new RecordingListener();
    tool.execute(
        request("call-cancelled", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"),
        cancelledListener);
    ToolResult cancelled = cancelledListener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(cancelled.error(), cancelled.toString());
    String cancelledText = ((TextToolContent) cancelled.contents().getFirst()).text();
    assertTrue(cancelledText.contains("(no textual report produced)"), cancelledText);
    assertEquals(
        "cancelled", new ObjectMapper().readTree(cancelled.detailsJson()).get("state").asText());
  }

  /** 状态 partial 携带 waiting_approval、toolCalls 统计与 waiting approval 明细。 */
  @Test
  void exposesWaitingApprovalsAndToolCallStatsInStatus() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    stubCreateFlow(runtime, childSettings);
    ToolApproval waiting = ToolApproval.request(NOW, "needs human");
    ToolApproval decided =
        new ToolApproval(true, ToolApprovalDecision.ALLOWED, id(1), "admin", null, NOW, NOW);
    ThreadSnapshot running =
        toolCallSnapshot(
            1L, List.of(toolInvocation(id(51), waiting), toolInvocation(id(52), decided)));
    ThreadSnapshot terminal = completedChildSnapshot(childRootSnapshot(childSettings), "done");
    stubChildSnapshots(runtime, childRootSnapshot(childSettings), running, terminal);
    RecordingListener listener = new RecordingListener();

    tool.execute(
        request("call-approval", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertFalse(result.error(), result.toString());
    assertTrue(listener.partials.size() >= 1, "must publish at least one status partial");
    JsonNode status = new ObjectMapper().readTree(listener.partials.getFirst().detailsJson());
    assertEquals("task.status", status.get("kind").asText());
    assertEquals("waiting_approval", status.get("state").asText());
    assertEquals(1, status.get("toolCalls").asInt());
    assertEquals(1, status.get("turns").asInt());
    assertEquals("running web_search", status.get("lastActivity").asText());
    JsonNode approvals = status.get("approvals");
    assertEquals(1, approvals.size());
    assertEquals(id(51).toString(), approvals.get(0).get("invocationId").asText());
    assertEquals("web_search", approvals.get(0).get("toolName").asText());
    assertEquals("needs human", approvals.get(0).get("reason").asText());
  }

  /** 祖先 task.status 必须携带嵌套子树状态，使根 Thread 可以直接审批任意深度的等待调用。 */
  @Test
  void relaysNestedTaskStatusesToTheRootListener() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    stubCreateFlow(runtime, childSettings);
    stubChildSnapshots(
        runtime,
        childRootSnapshot(childSettings),
        completedChildSnapshot(childRootSnapshot(childSettings), "done"));
    SubagentConfig nestedConfig = config(3, 10, Duration.ZERO, DEFAULT_MAX_TURNS);
    SubagentRunRegistry registry = new SubagentRunRegistry();
    tool = tool(nestedConfig, registry);
    try (SubagentRunRegistry.Reservation nested =
        registry.reserve(CHILD_THREAD_ID, PARENT_THREAD_ID, null, nestedConfig)) {
      nested.attach(id(44));
      registry.publishStatus(
          new SubagentRunRegistry.RelayedStatus(
              id(44),
              "coder",
              "waiting_approval",
              3,
              1,
              1,
              "waiting bash",
              List.of(new SubagentRunRegistry.RelayedApproval(id(71), "bash", "confirm"))));
      RecordingListener listener = new RecordingListener();

      tool.execute(
          request("call-nested", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);

      ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
      assertFalse(result.error(), result.toString());
      JsonNode status = new ObjectMapper().readTree(listener.partials.getFirst().detailsJson());
      JsonNode descendants = status.get("descendants");
      assertEquals(1, descendants.size());
      assertEquals(id(44).toString(), descendants.get(0).get("threadId").asText());
      assertEquals("waiting_approval", descendants.get(0).get("state").asText());
      assertEquals(
          id(71).toString(),
          descendants.get(0).get("approvals").get(0).get("invocationId").asText());
    }
  }

  /** 订阅必须先于观察循环的首次权威读取；首读期间到达的 version 信号不得丢失，唤醒后重读终态。 */
  @Test
  void subscribesBeforeObservationReadAndDoesNotLoseConcurrentSignal() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    stubCreateFlow(runtime, childSettings);
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot terminal = completedChildSnapshot(child, "done after race");
    AtomicInteger reads = new AtomicInteger();
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenAnswer(
            inv -> {
              int n = reads.incrementAndGet();
              if (n == 1) {
                return child; // createChild
              }
              if (n == 2) {
                // awaitResult 的首次权威读取必须发生在订阅之后（subscribe-before-read 无竞态窗口）。
                assertTrue(
                    changeSource.isSubscribed(CHILD_THREAD_ID),
                    "observation read must follow subscribe");
                // 首读进行期间到达的 version 信号不得丢失：计数在等待前已建立，唤醒后必然重读。
                changeSource.signal(CHILD_THREAD_ID);
                return child;
              }
              return terminal; // 并发信号唤醒后的权威重读
            });
    RecordingListener listener = new RecordingListener();

    tool.execute(
        request("call-race", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);

    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertFalse(result.error(), result.toString());
    assertEquals(3, reads.get(), "createChild + initial + concurrent-signal re-read only");
    assertEquals(0, changeSource.activeSubscriptions(CHILD_THREAD_ID), "success must release");
    assertEquals(1, changeSource.totalClosed());
  }

  /** descendant relay 经 registry 订阅唤醒观察循环并发布新 relay，但绝不重读 durable snapshot；随后 version wake 正常收尾。 */
  @Test
  void wakesOnDescendantRelayWithoutRereadingDurableSnapshot() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    stubCreateFlow(runtime, childSettings);
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot terminal = completedChildSnapshot(child, "done after relay");
    AtomicInteger reads = new AtomicInteger();
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenAnswer(
            inv -> {
              int n = reads.incrementAndGet();
              if (n <= 2) {
                return child; // createChild + await 首读（非终态，观察循环进入等待）
              }
              return terminal;
            });
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig nestedConfig = config(3, 10, Duration.ZERO, DEFAULT_MAX_TURNS);
    tool = tool(nestedConfig, registry);
    RecordingListener listener = new RecordingListener();

    tool.execute(
        request("call-relay", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);
    awaitInitialStatus(listener);

    try (SubagentRunRegistry.Reservation nested =
        registry.reserve(CHILD_THREAD_ID, ROOT_THREAD_ID, null, nestedConfig)) {
      nested.attach(id(44));
      registry.publishStatus(
          new SubagentRunRegistry.RelayedStatus(
              id(44),
              "coder",
              "waiting_approval",
              3,
              1,
              1,
              "waiting bash",
              List.of(new SubagentRunRegistry.RelayedApproval(id(71), "bash", "confirm"))));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (listener.partials.size() < 2 && System.nanoTime() < deadline) {
        Thread.sleep(1);
      }
      assertTrue(listener.partials.size() >= 2, "descendant relay must wake and republish status");
      assertEquals(2, reads.get(), "descendant relay must not re-read the durable snapshot");

      JsonNode latest = new ObjectMapper().readTree(listener.partials.getLast().detailsJson());
      assertEquals(1, latest.get("descendants").size());
      assertEquals(id(44).toString(), latest.get("descendants").get(0).get("threadId").asText());
    }

    // version 信号触发权威重读 → 终态。
    changeSource.signal(CHILD_THREAD_ID);
    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertFalse(result.error(), result.toString());
    assertEquals(3, reads.get(), "initial read plus version-wake terminal read only");
    assertEquals(0, changeSource.activeSubscriptions(CHILD_THREAD_ID));
  }

  /** maxTurns/session_id 的非法边界都稳定拒绝并给出可定位消息。 */
  @Test
  void rejectsInvalidArgumentBoundaries() throws Exception {
    assertRejected("{\"subagent_type\":\" \",\"prompt\":\"p\"}", "subagent_type is required");
    assertRejected(
        "{\"subagent_type\":\"a\",\"prompt\":\"p\",\"maxTurns\":0}",
        "maxTurns must be a positive integer");
    assertRejected(
        "{\"subagent_type\":\"a\",\"prompt\":\"p\",\"maxTurns\":-2}",
        "maxTurns must be a positive integer");
    assertRejected(
        "{\"subagent_type\":\"a\",\"prompt\":\"p\",\"maxTurns\":9999999999999}",
        "maxTurns must be a positive integer");
    assertRejected(
        "{\"subagent_type\":\"a\",\"prompt\":\"p\",\"session_id\":\"12.5\"}",
        "session_id must be a canonical UUID string");
    assertRejected(
        "{\"subagent_type\":\"a\",\"prompt\":\"p\",\"session_id\":\"0\"}",
        "session_id must be a canonical UUID string");
    assertRejected(
        "{\"subagent_type\":\"a\",\"prompt\":\"p\",\"session_id\":\"007\"}",
        "session_id must be a canonical UUID string");
    assertRejected(
        "{\"subagent_type\":\"a\",\"prompt\":\"p\",\"session_id\":\"-5\"}",
        "session_id must be a canonical UUID string");
    assertRejected(
        "{\"subagent_type\":\"a\",\"prompt\":\"p\",\"session_id\":\"abc\"}",
        "session_id must be a canonical UUID string");
    assertRejected(
        "{\"subagent_type\":\"a\",\"prompt\":\"p\",\"session_id\":\" 42 \"}",
        "session_id must be a canonical UUID string");
  }

  /** HarnessRuntime 不可用时以 listener.onError 上报，不伪造 ToolResult。 */
  @Test
  void failsWithListenerErrorWhenRuntimeIsUnavailable() throws Exception {
    RecordingListener listener = new RecordingListener();

    tool.execute(
        request("call-runtime", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);

    ExecutionException error =
        assertThrows(ExecutionException.class, () -> listener.completed.get(5, TimeUnit.SECONDS));
    assertInstanceOf(IllegalStateException.class, error.getCause());
    assertEquals("HarnessRuntime is not available", error.getCause().getMessage());
  }

  /** prompt 入队冲突以错误完成且不泄漏 reservation：同一 parent 可立即重试成功。 */
  @Test
  void rejectsPromptEnqueueConflictThenRecovers() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"),
            List.of(new SubagentBinding(childSettings.agentName(), "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    ThreadSnapshot terminal = completedChildSnapshot(child, "recovered report");
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    when(settingsMaterializer.materialize(eq(childSettings.agentName()), isNull()))
        .thenReturn(childSettings);
    // 序列 [child, child, terminal]：第一次执行 NEW_SESSION 接受抛冲突（失败），第二次执行恢复成功。
    stubChildSnapshots(runtime, child, child, terminal);
    AtomicInteger accepts = new AtomicInteger();
    when(runtime.acceptCommands(any(AcceptCommandsCommand.class), any(AcceptancePreflight.class)))
        .thenAnswer(
            inv -> {
              if (accepts.incrementAndGet() == 1) {
                throw new HarnessRuntimeConflictException(
                    HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR, "cursor moved");
              }
              return acceptedNewSession(child, inv.getArgument(0));
            });
    RecordingListener first = new RecordingListener();

    tool.execute(
        request("call-conflict", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), first);
    ToolResult failed = first.completed.get(5, TimeUnit.SECONDS);
    assertTrue(failed.error(), failed.toString());
    assertTrue(
        ((TextToolContent) failed.contents().getFirst())
            .text()
            .contains("subagent session changed before the task prompt could be queued"),
        failed.toString());
    assertEquals(1, first.completedCalls.get());

    RecordingListener second = new RecordingListener();
    tool.execute(
        request("call-recovery", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), second);
    ToolResult recovered = second.completed.get(5, TimeUnit.SECONDS);
    assertFalse(recovered.error(), recovered.toString());
    assertEquals(1, second.completedCalls.get());
  }

  /** parent 并发槽位耗尽时 reserve 的拒绝以错误 ToolResult 呈现。 */
  @Test
  void rejectsWhenParentConcurrencyLimitIsExhausted() throws Exception {
    SubagentConfig limited = config(2, 1, Duration.ZERO, DEFAULT_MAX_TURNS);
    SubagentRunRegistry registry = new SubagentRunRegistry();
    TaskTool limitedTool = tool(limited, registry);
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"), List.of(new SubagentBinding("reviewer", "Review")));
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    RecordingListener listener = new RecordingListener();

    try (SubagentRunRegistry.Reservation held =
        registry.reserve(PARENT_THREAD_ID, PARENT_THREAD_ID, null, limited)) {
      limitedTool.execute(
          request("call-limit", "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\"}"), listener);
      ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
      assertTrue(result.error(), result.toString());
      assertTrue(
          ((TextToolContent) result.contents().getFirst())
              .text()
              .contains("subagent concurrency limit reached (1/1)"),
          result.toString());
    }
    verify(runtime, never()).acceptCommands(any(), any());
  }

  /** 同一 resume session 正在运行时，第二次委派被进程内 reservation 拒绝。 */
  @Test
  void rejectsConcurrentResumeOfSameSession() throws Exception {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    this.runtime = runtime;
    BranchSettings settings = settings("parent", "parent-model");
    BranchSettings childSettings = settings("reviewer", "review-model");
    ThreadSnapshot parent =
        parentSnapshot(settings, List.of(new SubagentBinding("reviewer", "Review")));
    ThreadSnapshot resumed =
        quiescentResumeSnapshot(childSettings, SUBAGENT_CONTEXT, false, id(34), 5L);
    ThreadSnapshot running =
        runningRootSnapshot(RESUME_THREAD_ID, CHILD_ROOT_ENTRY_ID, 6L, List.of(), List.of());
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    when(settingsMaterializer.materialize(any(), isNull())).thenReturn(childSettings);
    AtomicInteger reads = new AtomicInteger();
    when(runtime.getThreadSnapshot(RESUME_THREAD_ID))
        .thenAnswer(
            inv -> {
              if (reads.incrementAndGet() == 1) {
                return resumed;
              }
              return running;
            });
    RecordingListener first = new RecordingListener();

    ToolExecutionHandle firstHandle =
        tool.execute(
            request(
                "call-first",
                "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\",\"session_id\":\""
                    + RESUME_THREAD_ID
                    + "\"}"),
            first);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (first.partials.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertFalse(first.partials.isEmpty(), "first run must publish a status partial");

    RecordingListener second = new RecordingListener();
    tool.execute(
        request(
            "call-second",
            "{\"subagent_type\":\"reviewer\",\"prompt\":\"p\",\"session_id\":\""
                + RESUME_THREAD_ID
                + "\"}"),
        second);
    ToolResult secondResult = second.completed.get(5, TimeUnit.SECONDS);
    assertTrue(secondResult.error(), secondResult.toString());
    assertTrue(
        ((TextToolContent) secondResult.contents().getFirst())
            .text()
            .contains("subagent session \"" + RESUME_THREAD_ID + "\" is currently running"),
        secondResult.toString());

    firstHandle.cancel();
    ToolResult firstResult = first.completed.get(5, TimeUnit.SECONDS);
    assertTrue(firstResult.error(), firstResult.toString());
    assertTrue(
        ((TextToolContent) firstResult.contents().getFirst())
            .text()
            .contains("Session preserved as `" + RESUME_THREAD_ID + "`"),
        firstResult.toString());
    assertEquals(1, first.completedCalls.get());
  }

  private void assertRejected(String argumentsJson, String messageFragment) throws Exception {
    RecordingListener listener = new RecordingListener();
    tool.execute(request("call-reject-" + callId++, argumentsJson), listener);
    ToolResult result = listener.completed.get(5, TimeUnit.SECONDS);
    assertTrue(result.error(), argumentsJson + " -> " + result);
    String text = ((TextToolContent) result.contents().getFirst()).text();
    assertTrue(text.contains(messageFragment), argumentsJson + " -> " + text);
  }

  /** 建立 create-child 的公共 stub：parent snapshot、child snapshot、NEW_SESSION 接受与 materialize。 */
  private void stubCreateFlow(HarnessRuntime runtime, BranchSettings childSettings) {
    ThreadSnapshot parent =
        parentSnapshot(
            settings("parent", "parent-model"),
            List.of(new SubagentBinding(childSettings.agentName(), "Review")));
    ThreadSnapshot child = childRootSnapshot(childSettings);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    when(settingsMaterializer.materialize(eq(childSettings.agentName()), isNull()))
        .thenReturn(childSettings);
    stubAcceptNewSession(runtime, child);
  }

  /** 让 NEW_SESSION 接受返回固定 child；acceptedCommands 从请求命令派生（与真实 HarnessRuntime 的分配语义一致）。 */
  private static void stubAcceptNewSession(HarnessRuntime runtime, ThreadSnapshot child) {
    when(runtime.acceptCommands(any(AcceptCommandsCommand.class), any(AcceptancePreflight.class)))
        .thenAnswer(inv -> acceptedNewSession(child, inv.getArgument(0)));
  }

  private static AcceptedCommands acceptedNewSession(
      ThreadSnapshot child, AcceptCommandsCommand command) {
    return new AcceptedCommands(
        new Session(child.entryPath().root().sessionId(), NOW),
        child.entryPath().root(),
        child.thread(),
        toThreadCommands(child.thread().id(), command.commands()),
        false);
  }

  /** 把 NEW_SESSION 请求命令映射为该 Thread 上 1 起始、严格递增 sequence 的 durable Commands。 */
  private static List<ThreadCommand> toThreadCommands(
      UUID threadId, List<NewThreadCommand> requests) {
    List<ThreadCommand> accepted = new ArrayList<>();
    long sequence = 1L;
    for (NewThreadCommand request : requests) {
      accepted.add(
          new ThreadCommand(
              threadId,
              sequence++,
              request.payload(),
              request.clientCommandId(),
              request.requestHash(),
              null,
              null,
              null,
              NOW));
    }
    return accepted;
  }

  private ToolExecutionRequest request(String callId, String argumentsJson) {
    return request(tool, callId, argumentsJson);
  }

  private ToolExecutionRequest request(TaskTool requestTool, String callId, String argumentsJson) {
    return new ToolExecutionRequest(
        requestTool.descriptor(),
        new ToolCall(callId, TaskTool.NAME, argumentsJson),
        Duration.ZERO,
        new ToolExecutionContext(TASK_INVOCATION_ID, PARENT_THREAD_ID));
  }

  /**
   * 绑定 thread 的 snapshot 序列：每次读取按序返回，并在「返回非末位状态」时预置一次 version wake——模拟 durable 每步推进都会先产生 version
   * 通知再被观察循环读取。返回末位后不再自动唤醒（避免额外重读污染计数）；返回序列外索引时稳定返回末位。
   */
  private void stubSnapshots(HarnessRuntime runtime, UUID threadId, ThreadSnapshot... sequence) {
    AtomicInteger reads = new AtomicInteger();
    when(runtime.getThreadSnapshot(threadId))
        .thenAnswer(
            inv -> {
              int n = reads.getAndIncrement();
              ThreadSnapshot snapshot = sequence[Math.min(n, sequence.length - 1)];
              if (n < sequence.length - 1) {
                changeSource.signal(threadId);
              }
              return snapshot;
            });
  }

  private void stubChildSnapshots(HarnessRuntime runtime, ThreadSnapshot... sequence) {
    stubSnapshots(runtime, CHILD_THREAD_ID, sequence);
  }

  /** 触发一次 child version wake；配合 {@link #stubChildSnapshots} 的末位不自动唤醒语义手动推进到末位。 */
  private void signalChild() {
    changeSource.signal(CHILD_THREAD_ID);
  }

  /** 等待观察循环完成订阅 + 首次权威读取 + 初始 status 发布；保证后续 signal 一定在订阅之后。 */
  private void awaitInitialStatus(RecordingListener listener) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (listener.partials.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertFalse(listener.partials.isEmpty(), "task must publish an initial status partial");
  }

  /** 构造绑定给定 Session 的合法持久化 Thread 状态（materializationHash 为 64 个 0）。 */
  private static ThreadState threadState(
      UUID threadId,
      UUID sessionId,
      UUID headEntryId,
      boolean yoloEnabled,
      long nextCommandSequence,
      long version) {
    return new ThreadState(
        threadId,
        sessionId,
        headEntryId,
        "0".repeat(64),
        yoloEnabled,
        nextCommandSequence,
        version,
        NOW,
        NOW);
  }

  private static ThreadSnapshot parentSnapshot(
      BranchSettings settings, List<SubagentBinding> allowedSubagents) {
    return parentSnapshot(null, settings, allowedSubagents, List.of(taskInvocationSibling()));
  }

  private static ThreadSnapshot parentSnapshot(
      SubagentContext rootContext,
      BranchSettings settings,
      List<SubagentBinding> allowedSubagents,
      List<ToolInvocation> toolSiblings) {
    Entry root =
        new Entry(id(1), PARENT_SESSION_ID, null, new RootPayload(settings, rootContext), NOW);
    ThreadState thread = threadState(PARENT_THREAD_ID, PARENT_SESSION_ID, root.id(), true, 1L, 0L);
    ModelRequestSpec modelRequest = mock(ModelRequestSpec.class);
    when(modelRequest.subagentBindings()).thenReturn(allowedSubagents);
    ModelInvocation model = mock(ModelInvocation.class);
    when(model.request()).thenReturn(modelRequest);
    return new ThreadSnapshot(
        thread, new EntryPath(List.of(root)), List.of(), model, toolSiblings, List.of());
  }

  private static ToolInvocation taskInvocationSibling() {
    ToolInvocation task = mock(ToolInvocation.class);
    when(task.id()).thenReturn(TASK_INVOCATION_ID);
    return task;
  }

  private static ThreadSnapshot childRootSnapshot(BranchSettings settings) {
    return childRootSnapshot(settings, SUBAGENT_CONTEXT);
  }

  private static ThreadSnapshot childRootSnapshot(
      BranchSettings settings, SubagentContext context) {
    Entry root =
        new Entry(CHILD_ROOT_ENTRY_ID, SESSION_ID, null, new RootPayload(settings, context), NOW);
    ThreadState thread = threadState(CHILD_THREAD_ID, SESSION_ID, root.id(), true, 1L, 0L);
    return new ThreadSnapshot(
        thread, new EntryPath(List.of(root)), List.of(), null, List.of(), List.of());
  }

  private static ThreadSnapshot completedChildSnapshot(ThreadSnapshot initial, String report) {
    Entry root = initial.entryPath().root();
    Entry turn =
        new Entry(
            id(34),
            root.sessionId(),
            root.id(),
            new TurnStartPayload(
                TurnStartReason.CONTINUATION, initial.entryPath().baseSettings(), OWNER_THREAD_ID),
            NOW);
    Entry assistant = new Entry(id(35), root.sessionId(), turn.id(), assistantMessage(report), NOW);
    Entry end =
        new Entry(
            id(36),
            root.sessionId(),
            assistant.id(),
            new TurnEndPayload(turn.id(), TurnEndOutcome.COMPLETED, false, null, null),
            NOW);
    ThreadState thread =
        threadState(
            CHILD_THREAD_ID,
            initial.thread().sessionId(),
            end.id(),
            initial.thread().yoloEnabled(),
            2L,
            2L);
    return new ThreadSnapshot(
        thread,
        new EntryPath(List.of(root, turn, assistant, end)),
        List.of(),
        null,
        List.of(),
        List.of());
  }

  private static ThreadSnapshot quiescentResumeSnapshot(
      BranchSettings settings,
      SubagentContext context,
      boolean continueModel,
      UUID headEntryId,
      long version) {
    Entry root =
        new Entry(CHILD_ROOT_ENTRY_ID, SESSION_ID, null, new RootPayload(settings, context), NOW);
    Entry turn =
        new Entry(
            id(32),
            root.sessionId(),
            root.id(),
            new TurnStartPayload(TurnStartReason.CONTINUATION, settings, OWNER_THREAD_ID),
            NOW);
    Entry assistant =
        new Entry(
            id(33),
            root.sessionId(),
            turn.id(),
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new TextMessageContent("earlier"), new JsonMessageContent("{\"k\":1}"))),
                assistantMetadata(),
                null),
            NOW);
    Entry end =
        new Entry(
            id(34),
            root.sessionId(),
            assistant.id(),
            new TurnEndPayload(turn.id(), TurnEndOutcome.COMPLETED, continueModel, null, null),
            NOW);
    ThreadState thread = threadState(RESUME_THREAD_ID, SESSION_ID, headEntryId, true, 2L, version);
    return new ThreadSnapshot(
        thread,
        new EntryPath(List.of(root, turn, assistant, end)),
        List.of(),
        null,
        List.of(),
        List.of());
  }

  private static ThreadSnapshot resumeTerminalSnapshot(ThreadSnapshot resumed, String report) {
    List<Entry> entries = new ArrayList<>(resumed.entryPath().entries());
    Entry last = entries.get(entries.size() - 1);
    Entry turn =
        new Entry(
            id(100),
            last.sessionId(),
            last.id(),
            new TurnStartPayload(
                TurnStartReason.CONTINUATION, resumed.entryPath().baseSettings(), OWNER_THREAD_ID),
            NOW);
    Entry assistant =
        new Entry(
            id(101),
            last.sessionId(),
            turn.id(),
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new TextMessageContent(report), new JsonMessageContent("{\"k\":1}"))),
                assistantMetadata(),
                null),
            NOW);
    Entry end =
        new Entry(
            id(102),
            last.sessionId(),
            assistant.id(),
            new TurnEndPayload(turn.id(), TurnEndOutcome.COMPLETED, false, null, null),
            NOW);
    entries.add(turn);
    entries.add(assistant);
    entries.add(end);
    ThreadState thread =
        threadState(
            resumed.thread().id(),
            resumed.thread().sessionId(),
            end.id(),
            resumed.thread().yoloEnabled(),
            resumed.thread().nextCommandSequence(),
            resumed.thread().version() + 1);
    return new ThreadSnapshot(
        thread, new EntryPath(entries), List.of(), null, List.of(), List.of());
  }

  private static ThreadSnapshot runningRootSnapshot(
      UUID threadId,
      UUID headEntryId,
      long version,
      List<ToolInvocation> tools,
      List<ThreadCommand> queued) {
    BranchSettings settings = settings("reviewer", "review-model");
    Entry root =
        new Entry(
            CHILD_ROOT_ENTRY_ID,
            SESSION_ID,
            null,
            new RootPayload(settings, SUBAGENT_CONTEXT),
            NOW);
    ThreadState thread = threadState(threadId, SESSION_ID, headEntryId, true, 1L, version);
    return new ThreadSnapshot(thread, new EntryPath(List.of(root)), queued, null, tools, List.of());
  }

  private static ThreadSnapshot toolCallSnapshot(long version, List<ToolInvocation> tools) {
    BranchSettings settings = settings("reviewer", "review-model");
    Entry root =
        new Entry(
            CHILD_ROOT_ENTRY_ID,
            SESSION_ID,
            null,
            new RootPayload(settings, SUBAGENT_CONTEXT),
            NOW);
    Entry turn =
        new Entry(
            id(200),
            root.sessionId(),
            root.id(),
            new TurnStartPayload(TurnStartReason.CONTINUATION, settings, OWNER_THREAD_ID),
            NOW);
    Entry assistant =
        new Entry(
            id(201),
            root.sessionId(),
            turn.id(),
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new ToolCallMessageContent("tc-1", "web_search", "web_search", "{}"))),
                new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, COST),
                null),
            NOW);
    ThreadState thread =
        threadState(CHILD_THREAD_ID, SESSION_ID, assistant.id(), true, 1L, version);
    return new ThreadSnapshot(
        thread, new EntryPath(List.of(root, turn, assistant)), List.of(), null, tools, List.of());
  }

  /**
   * 构造运行中快照：首个 COMPACTION turn（以 CANCELLED 关闭，countTurns 不计数），后跟 N 个 CONTINUATION turn（最后一个 open）。
   */
  private static ThreadSnapshot turnCountSnapshot(int turns, ModelInvocation model, long version) {
    BranchSettings settings = settings("reviewer", "review-model");
    List<Entry> entries = new ArrayList<>();
    Entry root =
        new Entry(
            CHILD_ROOT_ENTRY_ID,
            SESSION_ID,
            null,
            new RootPayload(settings, SUBAGENT_CONTEXT),
            NOW);
    entries.add(root);
    long nextId = 1000L;
    UUID parent = root.id();
    Entry compactionTurn =
        new Entry(
            id(nextId++),
            SESSION_ID,
            parent,
            new TurnStartPayload(
                TurnStartReason.COMPACTION,
                settings,
                OWNER_THREAD_ID,
                null,
                null,
                new CompactionStart(
                    CompactionPhase.FULL,
                    CompactionTrigger.THRESHOLD,
                    settings.model(),
                    root.id(),
                    null,
                    null)),
            NOW);
    Entry compactionEnd =
        new Entry(
            id(nextId++),
            SESSION_ID,
            compactionTurn.id(),
            new TurnEndPayload(
                compactionTurn.id(),
                TurnEndOutcome.CANCELLED,
                false,
                TurnEndReason.CANCELLED,
                null),
            NOW);
    entries.add(compactionTurn);
    entries.add(compactionEnd);
    parent = compactionEnd.id();
    for (int i = 0; i < turns; i++) {
      Entry turn =
          new Entry(
              id(nextId++),
              SESSION_ID,
              parent,
              new TurnStartPayload(TurnStartReason.CONTINUATION, settings, OWNER_THREAD_ID),
              NOW);
      Entry assistant =
          new Entry(id(nextId++), SESSION_ID, turn.id(), assistantMessage("turn " + i), NOW);
      entries.add(turn);
      entries.add(assistant);
      parent = assistant.id();
      if (i < turns - 1) {
        Entry end =
            new Entry(
                id(nextId++),
                SESSION_ID,
                assistant.id(),
                new TurnEndPayload(turn.id(), TurnEndOutcome.COMPLETED, true, null, null),
                NOW);
        entries.add(end);
        parent = end.id();
      }
    }
    ThreadState thread = threadState(CHILD_THREAD_ID, SESSION_ID, parent, true, 1L, version);
    return new ThreadSnapshot(
        thread, new EntryPath(entries), List.of(), model, List.of(), List.of());
  }

  private static ThreadSnapshot continueModelHeadSnapshot(boolean continueModel, long version) {
    BranchSettings settings = settings("reviewer", "review-model");
    Entry root =
        new Entry(
            CHILD_ROOT_ENTRY_ID,
            SESSION_ID,
            null,
            new RootPayload(settings, SUBAGENT_CONTEXT),
            NOW);
    Entry turn =
        new Entry(
            id(300),
            root.sessionId(),
            root.id(),
            new TurnStartPayload(TurnStartReason.CONTINUATION, settings, OWNER_THREAD_ID),
            NOW);
    Entry assistant =
        new Entry(id(301), root.sessionId(), turn.id(), assistantMessage("pending"), NOW);
    Entry end =
        new Entry(
            id(302),
            root.sessionId(),
            assistant.id(),
            new TurnEndPayload(turn.id(), TurnEndOutcome.COMPLETED, continueModel, null, null),
            NOW);
    ThreadState thread = threadState(CHILD_THREAD_ID, SESSION_ID, end.id(), true, 1L, version);
    return new ThreadSnapshot(
        thread,
        new EntryPath(List.of(root, turn, assistant, end)),
        List.of(),
        null,
        List.of(),
        List.of());
  }

  private static ThreadSnapshot failedTerminalSnapshot() {
    BranchSettings settings = settings("reviewer", "review-model");
    Entry root =
        new Entry(
            CHILD_ROOT_ENTRY_ID,
            SESSION_ID,
            null,
            new RootPayload(settings, SUBAGENT_CONTEXT),
            NOW);
    Entry turn =
        new Entry(
            id(400),
            root.sessionId(),
            root.id(),
            new TurnStartPayload(TurnStartReason.CONTINUATION, settings, OWNER_THREAD_ID),
            NOW);
    Entry error =
        new Entry(
            id(401),
            root.sessionId(),
            turn.id(),
            new AssistantErrorPayload(new AssistantError("CHILD_FAILED", "child exploded"), null),
            NOW);
    Entry end =
        new Entry(
            id(402),
            root.sessionId(),
            error.id(),
            new TurnEndPayload(
                turn.id(), TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
            NOW);
    ThreadState thread = threadState(CHILD_THREAD_ID, SESSION_ID, end.id(), true, 1L, 3L);
    return new ThreadSnapshot(
        thread,
        new EntryPath(List.of(root, turn, error, end)),
        List.of(),
        null,
        List.of(),
        List.of());
  }

  private static ThreadSnapshot stoppedTerminalSnapshot() {
    BranchSettings settings = settings("reviewer", "review-model");
    Entry root =
        new Entry(
            CHILD_ROOT_ENTRY_ID,
            SESSION_ID,
            null,
            new RootPayload(settings, SUBAGENT_CONTEXT),
            NOW);
    Entry turn =
        new Entry(
            id(410),
            root.sessionId(),
            root.id(),
            new TurnStartPayload(TurnStartReason.CONTINUATION, settings, OWNER_THREAD_ID),
            NOW);
    Entry aborted =
        new Entry(
            id(411),
            root.sessionId(),
            turn.id(),
            new AssistantAbortedPayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new TextMessageContent("stopped by user")))),
            NOW);
    Entry end =
        new Entry(
            id(412),
            root.sessionId(),
            aborted.id(),
            new TurnEndPayload(
                turn.id(), TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, id(1)),
            NOW);
    ThreadState thread = threadState(CHILD_THREAD_ID, SESSION_ID, end.id(), true, 1L, 3L);
    return new ThreadSnapshot(
        thread,
        new EntryPath(List.of(root, turn, aborted, end)),
        List.of(),
        null,
        List.of(),
        List.of());
  }

  private static ThreadSnapshot cancelledTerminalSnapshot() {
    BranchSettings settings = settings("reviewer", "review-model");
    Entry root =
        new Entry(
            CHILD_ROOT_ENTRY_ID,
            SESSION_ID,
            null,
            new RootPayload(settings, SUBAGENT_CONTEXT),
            NOW);
    Entry turn =
        new Entry(
            id(420),
            root.sessionId(),
            root.id(),
            new TurnStartPayload(TurnStartReason.CONTINUATION, settings, OWNER_THREAD_ID),
            NOW);
    Entry end =
        new Entry(
            id(421),
            root.sessionId(),
            turn.id(),
            new TurnEndPayload(
                turn.id(), TurnEndOutcome.CANCELLED, false, TurnEndReason.CANCELLED, null),
            NOW);
    ThreadState thread = threadState(CHILD_THREAD_ID, SESSION_ID, end.id(), true, 1L, 3L);
    return new ThreadSnapshot(
        thread, new EntryPath(List.of(root, turn, end)), List.of(), null, List.of(), List.of());
  }

  private static MessagePayload assistantMessage(String text) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
        assistantMetadata(),
        null);
  }

  private static AssistantMessageMetadata assistantMetadata() {
    return new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, COST);
  }

  private static ThreadCommand queuedCommand() {
    UserMessageCommandPayload payload = new UserMessageCommandPayload(AgentMessage.user("queued"));
    return new ThreadCommand(
        CHILD_THREAD_ID,
        1L,
        payload,
        id(1),
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        null,
        null,
        null,
        NOW);
  }

  private static ModelInvocation modelInvocation(UUID id) {
    ModelInvocation model = mock(ModelInvocation.class);
    when(model.id()).thenReturn(id);
    when(model.attempt()).thenReturn(1);
    when(model.status()).thenReturn(ModelInvocationStatus.RUNNING);
    when(model.updatedAt()).thenReturn(NOW);
    return model;
  }

  private static ToolInvocation toolInvocation(UUID id, ToolApproval approval) {
    ToolInvocation tool = mock(ToolInvocation.class);
    when(tool.id()).thenReturn(id);
    when(tool.call()).thenReturn(new ToolCall("tc-" + id, "web_search", "{}"));
    when(tool.status()).thenReturn(ToolInvocationStatus.RUNNING);
    when(tool.attempt()).thenReturn(1);
    when(tool.updatedAt()).thenReturn(NOW);
    when(tool.approval()).thenReturn(approval);
    return tool;
  }

  private static BranchSettings settings(String agent, String model) {
    return new BranchSettings(null, agent, new ModelSelection("provider", model, "default"));
  }

  private static final class RecordingListener implements ToolExecutionListener {
    private final List<ToolResult> partials = new CopyOnWriteArrayList<>();
    private final CompletableFuture<ToolResult> completed = new CompletableFuture<>();
    private final AtomicInteger completedCalls = new AtomicInteger();

    @Override
    public void onPartial(ToolResult partial) {
      partials.add(partial);
    }

    @Override
    public void onComplete(ToolResult result) {
      completedCalls.incrementAndGet();
      completed.complete(result);
    }

    @Override
    public void onError(Throwable error) {
      completedCalls.incrementAndGet();
      completed.completeExceptionally(error);
    }
  }
}

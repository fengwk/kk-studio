package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SubagentContext;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.testing.TestThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.harness.subagent.SubagentRunRegistry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * task 子会话的物化与恢复收敛边界。
 *
 * <p>测试意图：新建与恢复都按被调用 Agent 的 {@code inheritParentEnvironment} 使用父 Model invocation 冻结的
 * Environment（该 name 是唯一 durable 父子环境事实），恢复按固定命令前缀顺序收敛，而递归深度只在调用期拒绝。
 */
class DatabaseSubagentRunnerTest {

  private static final UUID PARENT_THREAD_ID = id(1);
  private static final UUID PARENT_SESSION_ID = id(2);
  private static final UUID PARENT_MODEL_ID = id(3);
  private static final UUID PARENT_ASSISTANT_ENTRY_ID = id(4);
  private static final UUID TASK_INVOCATION_ID = id(5);
  private static final UUID CHILD_THREAD_ID = id(6);
  private static final UUID CHILD_SESSION_ID = id(7);
  private static final String CALL_ID = "call-task";
  private static final String SUBAGENT = "reviewer";
  private static final String ENV_A = "env-a";
  private static final String ENV_B = "env-b";
  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

  private static final ModelSelection MODEL = new ModelSelection("openai", "gpt-x", "quality");

  private AgentDefinitionRepository agentRepository;
  private AgentModelRepository modelRepository;
  private HarnessRuntime runtime;
  private DatabaseSubagentRunner runner;

  private final AtomicReference<AcceptCommandsCommand> accepted = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    agentRepository = mock(AgentDefinitionRepository.class);
    modelRepository = mock(AgentModelRepository.class);
    runtime = mock(HarnessRuntime.class);
    AgentBranchSettingsMaterializer materializer =
        new AgentBranchSettingsMaterializer(
            agentRepository,
            modelRepository,
            new AgentModelRuntimeConfigParser(new ObjectMapper()),
            new AgentDefinitionConfigCodec(new ObjectMapper()));
    SubagentConfig subagentConfig = new SubagentConfig(2, 10, 0, Duration.ZERO, 50);
    runner =
        new DatabaseSubagentRunner(
            () -> runtime,
            materializer,
            () -> subagentConfig,
            new SubagentRunRegistry(),
            new TestThreadChangeSource(),
            directExecutor(),
            new ObjectMapper());
  }

  /** 缺省开关（true）的新建子会话继承调用方 Model invocation 冻结的环境。 */
  @Test
  void newChildSessionInheritsFrozenParentEnvironmentByDefault() {
    stubSubagent(true);
    stubParent(ENV_B, 1);
    RecordingListener listener = runNewTask();

    assertEquals(new BranchSettings(SUBAGENT, MODEL, ENV_B), listener.newSession().rootSettings());
  }

  /** 开关为 false 的新建子会话不继承父环境，即使父调用冻结了环境。 */
  @Test
  void newChildSessionDoesNotInheritWhenAgentDisablesIt() {
    stubSubagent(false);
    stubParent(ENV_B, 1);
    RecordingListener listener = runNewTask();

    assertNull(listener.newSession().rootSettings().environmentName());
  }

  /** 父 branch 未选择环境时，继承开关为 true 也不会凭空造出环境。 */
  @Test
  void newChildSessionKeepsNullWhenParentHasNoEnvironment() {
    stubSubagent(true);
    stubParent(null, 1);
    RecordingListener listener = runNewTask();

    assertNull(listener.newSession().rootSettings().environmentName());
  }

  /** 恢复子会话在父环境不同时按固定前缀发出 SET_ENVIRONMENT，再追加 USER。 */
  @Test
  void resumeConvergesEnvironmentWithSetEnvironmentCommand() {
    stubSubagent(true);
    stubParent(ENV_B, 1);
    RecordingListener listener = runResumeTask(SUBAGENT, MODEL, ENV_A);

    List<NewThreadCommand> commands = listener.enqueued();
    assertEquals(2, commands.size());
    assertEquals(
        ENV_B,
        assertInstanceOf(SetEnvironmentCommandPayload.class, commands.get(0).payload())
            .environmentName());
    assertInstanceOf(UserMessageCommandPayload.class, commands.get(1).payload());
  }

  /** 目标环境为 null 时 SET_ENVIRONMENT 仍显式发出，表达「清除该 branch 的环境选择」。 */
  @Test
  void resumeClearsEnvironmentWhenTargetHasNone() {
    stubSubagent(false);
    stubParent(ENV_B, 1);
    RecordingListener listener = runResumeTask(SUBAGENT, MODEL, ENV_A);

    List<NewThreadCommand> commands = listener.enqueued();
    assertEquals(2, commands.size());
    assertNull(
        assertInstanceOf(SetEnvironmentCommandPayload.class, commands.get(0).payload())
            .environmentName());
  }

  /** 三项设置都不同时严格按 SET_AGENT -> SET_MODEL -> SET_ENVIRONMENT -> USER 顺序收敛。 */
  @Test
  void resumeUsesFixedSettingCommandPrefixOrder() {
    stubSubagent(true);
    stubParent(ENV_B, 1);
    RecordingListener listener =
        runResumeTask("legacy", new ModelSelection("openai", "gpt-legacy", "old"), ENV_A);

    List<NewThreadCommand> commands = listener.enqueued();
    assertEquals(4, commands.size());
    assertEquals(SUBAGENT, ((SetAgentCommandPayload) commands.get(0).payload()).agentName());
    assertEquals(MODEL, ((SetModelCommandPayload) commands.get(1).payload()).model());
    assertEquals(
        ENV_B,
        assertInstanceOf(SetEnvironmentCommandPayload.class, commands.get(2).payload())
            .environmentName());
    assertInstanceOf(UserMessageCommandPayload.class, commands.get(3).payload());
  }

  /** 环境已与目标一致时不发送冗余 SET_ENVIRONMENT。 */
  @Test
  void resumeSkipsRedundantEnvironmentCommand() {
    stubSubagent(true);
    stubParent(ENV_B, 1);
    RecordingListener listener = runResumeTask(SUBAGENT, MODEL, ENV_B);

    List<NewThreadCommand> commands = listener.enqueued();
    assertEquals(1, commands.size());
    assertInstanceOf(UserMessageCommandPayload.class, commands.get(0).payload());
  }

  /** 测试意图：验证已到达部署 maxDepth 的调用仍带着冻结的 subagent binding 进入 runner，并在实际调用时以稳定错误拒绝，而不是静默执行或 静默成功。 */
  @Test
  void rejectsInvocationAtMaxDepthWithStableError() {
    stubSubagent(true);
    stubParent(ENV_B, 2);

    RecordingListener listener = new RecordingListener();
    runner.run(
        new SubagentTaskRequest(
            TASK_INVOCATION_ID, PARENT_THREAD_ID, "Investigate the failure.", SUBAGENT, null, null),
        listener);

    ToolResult result = listener.completed();
    assertTrue(result.error(), result.detailsJson());
    assertTrue(textOf(result).contains("subagent max depth reached: 2/2"), textOf(result));
  }

  // ---------- 执行入口 ----------

  /** 新建路径：第一次读回是 NEW_SESSION 接受后的 root-only 快照，其后读回已完成的子 turn。 */
  private RecordingListener runNewTask() {
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(childRootOnly(), childCompleted());
    return run(null);
  }

  /** 恢复路径：第一次读回是给定设置的静止子会话，其后读回追加了一个新 turn 以形成终态。 */
  private RecordingListener runResumeTask(
      String currentAgentName, ModelSelection currentModel, String currentEnvironmentName) {
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenReturn(
            childCompleted(currentAgentName, currentModel, currentEnvironmentName),
            childCompletedThenAnotherTurn(currentAgentName, currentModel, currentEnvironmentName));
    return run(CHILD_THREAD_ID);
  }

  private RecordingListener run(UUID resumeSessionId) {
    RecordingListener listener = new RecordingListener();
    runner.run(
        new SubagentTaskRequest(
            TASK_INVOCATION_ID,
            PARENT_THREAD_ID,
            "Investigate the failure.",
            SUBAGENT,
            null,
            resumeSessionId),
        listener);
    return listener;
  }

  // ---------- 桩 ----------

  private void stubSubagent(boolean inheritParentEnvironment) {
    AgentDefinition agent = new AgentDefinition();
    agent.setName(SUBAGENT);
    agent.setDescription("Review the change.");
    agent.setModelProviderName("openai");
    agent.setModelName("gpt-x");
    agent.setConfigJson(
        "{\"tools\":[],\"skills\":[],\"subagents\":[],\"inheritParentEnvironment\":"
            + inheritParentEnvironment
            + "}");
    when(agentRepository.getByName(SUBAGENT)).thenReturn(agent);

    AgentModel model = new AgentModel();
    model.setProviderName("openai");
    model.setName("gpt-x");
    model.setModelId("gpt-x-wire");
    model.setConfigJson(MODEL_CONFIG);
    when(modelRepository.getByProviderNameAndName("openai", "gpt-x")).thenReturn(model);
  }

  /** 冻结父 Model invocation：branch 环境、允许委派的 subagent 与调用期递归深度（root 为 1）。 */
  private void stubParent(String parentEnvironmentName, int depth) {
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID))
        .thenReturn(parentSnapshot(parentEnvironmentName, depth));
    when(runtime.acceptCommands(any(AcceptCommandsCommand.class), any(AcceptancePreflight.class)))
        .thenAnswer(
            invocation -> {
              AcceptCommandsCommand command = invocation.getArgument(0);
              accepted.set(command);
              return acceptedCommands(command);
            });
  }

  private AcceptedCommands acceptedCommands(AcceptCommandsCommand command) {
    List<ThreadCommand> commands = new ArrayList<>();
    long sequence = 1L;
    for (NewThreadCommand request : command.commands()) {
      commands.add(
          new ThreadCommand(
              CHILD_THREAD_ID,
              sequence++,
              request.payload(),
              request.idempotencyKey(),
              request.requestHash(),
              null,
              null,
              null,
              NOW));
    }
    Entry root = childRoot(SUBAGENT, MODEL, ENV_B);
    ThreadState thread =
        new ThreadState(
            CHILD_THREAD_ID,
            CHILD_SESSION_ID,
            root.id(),
            "0".repeat(64),
            "child",
            false,
            commands.size() + 1L,
            0L,
            NOW,
            NOW);
    return new AcceptedCommands(
        new Session(CHILD_SESSION_ID, "child-session", NOW), root, thread, commands, false);
  }

  // ---------- 快照构造 ----------

  private ThreadSnapshot parentSnapshot(String parentEnvironmentName, int depth) {
    Entry root =
        new Entry(
            id(20),
            PARENT_SESSION_ID,
            null,
            new RootPayload(
                new BranchSettings("assistant", MODEL, parentEnvironmentName),
                depth <= 1
                    ? null
                    : new SubagentContext(PARENT_THREAD_ID, PARENT_THREAD_ID, id(21), depth)),
            NOW);
    ToolInvocation taskInvocation =
        new ToolInvocation(
            TASK_INVOCATION_ID,
            PARENT_MODEL_ID,
            PARENT_ASSISTANT_ENTRY_ID,
            0,
            new ToolCall(CALL_ID, "task", "{}"),
            taskBinding(),
            ToolInvocationStatus.RUNNING,
            1,
            ToolApproval.notRequired(),
            null,
            null,
            NOW,
            NOW);
    return new ThreadSnapshot(
        thread(PARENT_THREAD_ID, PARENT_SESSION_ID, root.id()),
        new EntryPath(List.of(root)),
        List.of(),
        parentInvocation(),
        List.of(taskInvocation),
        List.of());
  }

  private ModelInvocation parentInvocation() {
    return new ModelInvocation(
        PARENT_MODEL_ID,
        PARENT_THREAD_ID,
        id(22),
        id(20),
        new ModelRequestSpec(
            ProviderType.OPENAI,
            id(23),
            modelDescriptor(),
            new ModelVariant("default"),
            1024,
            List.of(),
            List.of(),
            List.of(),
            List.of(new SubagentBinding(SUBAGENT, "Review the change.")),
            ProviderCacheControl.none()),
        ModelInvocationStatus.READY,
        0,
        null,
        null,
        null,
        null,
        List.of(),
        NOW,
        NOW);
  }

  /** NEW_SESSION 接受后的子会话：只有 ROOT，head 指向该 ROOT。 */
  private ThreadSnapshot childRootOnly() {
    return snapshot(List.of(childRoot(SUBAGENT, MODEL, ENV_B)));
  }

  /** 已关闭一个 COMPLETED turn 的静止子会话。 */
  private ThreadSnapshot childCompleted() {
    return childCompleted(SUBAGENT, MODEL, ENV_B);
  }

  private ThreadSnapshot childCompleted(
      String agentName, ModelSelection selection, String environmentName) {
    List<Entry> entries = new ArrayList<>();
    entries.add(childRoot(agentName, selection, environmentName));
    UUID parentId = entries.getFirst().id();
    addCompletedTurn(entries, agentName, selection, environmentName, parentId, 31L);
    return snapshot(entries);
  }

  /** 既有 turn 之后追加第二个 COMPLETED turn，使观察循环能识别第一个 turn 之后的终态。 */
  private ThreadSnapshot childCompletedThenAnotherTurn(
      String agentName, ModelSelection selection, String environmentName) {
    List<Entry> entries = new ArrayList<>();
    entries.add(childRoot(agentName, selection, environmentName));
    UUID parentId = entries.getFirst().id();
    addCompletedTurn(entries, agentName, selection, environmentName, parentId, 31L);
    addCompletedTurn(entries, agentName, selection, environmentName, entries.getLast().id(), 41L);
    return snapshot(entries);
  }

  private Entry childRoot(String agentName, ModelSelection selection, String environmentName) {
    return new Entry(
        id(30),
        CHILD_SESSION_ID,
        null,
        new RootPayload(
            new BranchSettings(agentName, selection, environmentName),
            new SubagentContext(PARENT_THREAD_ID, PARENT_THREAD_ID, TASK_INVOCATION_ID, 2)),
        NOW);
  }

  /** 把 INPUT + USER + ASSISTANT + TURN_END 四条 entry 追加到给定 parent 之后。 */
  private void addCompletedTurn(
      List<Entry> entries,
      String agentName,
      ModelSelection selection,
      String environmentName,
      UUID parentId,
      long baseId) {
    BranchSettings settings = new BranchSettings(agentName, selection, environmentName);
    long epoch = baseId;
    Entry turnStart =
        new Entry(
            id(baseId),
            CHILD_SESSION_ID,
            parentId,
            new TurnStartPayload(TurnStartReason.INPUT, settings, CHILD_THREAD_ID),
            NOW.plusSeconds(epoch));
    Entry user =
        new Entry(
            id(baseId + 1),
            CHILD_SESSION_ID,
            turnStart.id(),
            new MessagePayload(AgentMessage.user("Investigate the failure."), null, null),
            NOW.plusSeconds(epoch + 1));
    Entry assistant =
        new Entry(
            id(baseId + 2),
            CHILD_SESSION_ID,
            user.id(),
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("report"))),
                assistantMetadata(),
                null),
            NOW.plusSeconds(epoch + 2));
    Entry turnEnd =
        new Entry(
            id(baseId + 3),
            CHILD_SESSION_ID,
            assistant.id(),
            new TurnEndPayload(turnStart.id(), TurnEndOutcome.COMPLETED, false, null, null),
            NOW.plusSeconds(epoch + 3));
    entries.add(turnStart);
    entries.add(user);
    entries.add(assistant);
    entries.add(turnEnd);
  }

  private ThreadSnapshot snapshot(List<Entry> entries) {
    Entry head = entries.get(entries.size() - 1);
    return new ThreadSnapshot(
        thread(CHILD_THREAD_ID, CHILD_SESSION_ID, head.id()),
        new EntryPath(List.copyOf(entries)),
        List.of(),
        null,
        List.of(),
        List.of());
  }

  private static ThreadState thread(UUID threadId, UUID sessionId, UUID headEntryId) {
    return new ThreadState(
        threadId, sessionId, headEntryId, "0".repeat(64), "thread", false, 2L, 0L, NOW, NOW);
  }

  private static AssistantMessageMetadata assistantMetadata() {
    return new AssistantMessageMetadata(
        GenerationStopReason.COMPLETE,
        new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static ToolBinding taskBinding() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "task",
            "delegate a task",
            "task",
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ZERO);
    return new ToolBinding(
        new AgentToolDefinition(descriptor, ToolVisibility.INTERNAL),
        new ContributorBinding("core", "task", List.of()),
        false,
        null);
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "openai",
        "gpt-x",
        "gpt-x-wire",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        new ModelPricing(
            "USD",
            "standard",
            "standard",
            BigDecimal.ONE,
            "1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static String textOf(ToolResult result) {
    StringBuilder text = new StringBuilder();
    for (var content : result.contents()) {
      if (content instanceof TextResultContent textContent) {
        text.append(textContent.text());
      }
    }
    return text.toString();
  }

  private static ExecutorService directExecutor() {
    return new AbstractExecutorService() {
      @Override
      public void execute(Runnable command) {
        command.run();
      }

      @Override
      public void shutdown() {}

      @Override
      public List<Runnable> shutdownNow() {
        return List.of();
      }

      @Override
      public boolean isShutdown() {
        return false;
      }

      @Override
      public boolean isTerminated() {
        return false;
      }

      @Override
      public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
      }
    };
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static final String MODEL_CONFIG =
      "{\"limit\":{\"context\":128000,\"output\":8192},"
          + "\"abilities\":{\"tools\":true,\"reasoning\":true,"
          + "\"inputModalities\":[\"TEXT\",\"IMAGE\"]},"
          + "\"defaultVariant\":\"quality\","
          + "\"variants\":[{\"id\":\"quality\",\"reasoningEffort\":\"high\"},"
          + "{\"id\":\"fast\",\"reasoningEffort\":\"off\"}],"
          + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"batch\","
          + "\"serviceTier\":\"priority\",\"serviceTierMultiplier\":1.25,"
          + "\"version\":\"2026-07-16\",\"inputPerMillionTokens\":1.1,"
          + "\"outputPerMillionTokens\":2.2,\"cacheReadPerMillionTokens\":0.3,"
          + "\"cacheWritePerMillionTokens\":0.4,"
          + "\"cacheWriteLongPerMillionTokens\":0.5,"
          + "\"reasoningPerMillionTokens\":3.6}}";

  /** 收集 task 终态结果与 acceptCommands 入参。 */
  private final class RecordingListener implements ToolExecutionListener {

    private ToolResult result;

    @Override
    public void onPartial(ToolResult partial) {
      // 状态心跳不参与本测试的断言。
    }

    @Override
    public void onComplete(ToolOutcome outcome) {
      this.result = outcome.result();
    }

    @Override
    public void onError(Throwable error) {
      throw new AssertionError("task execution failed unexpectedly", error);
    }

    private ToolResult completed() {
      assertNotNull(result, "task must reach a terminal result");
      return result;
    }

    private AcceptCommandsTarget.NewSession newSession() {
      return assertInstanceOf(AcceptCommandsTarget.NewSession.class, acceptedCommand().target());
    }

    private List<NewThreadCommand> enqueued() {
      return acceptedCommand().commands();
    }

    private AcceptCommandsCommand acceptedCommand() {
      AcceptCommandsCommand command = accepted.get();
      assertNotNull(command, "task must accept a command batch");
      return command;
    }
  }
}

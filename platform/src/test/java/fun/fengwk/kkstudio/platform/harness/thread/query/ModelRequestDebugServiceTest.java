package fun.fengwk.kkstudio.platform.harness.thread.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.environment.skill.SkillPromptResolution;
import fun.fengwk.kkstudio.platform.harness.task.AgentPromptComposer;
import fun.fengwk.kkstudio.platform.harness.task.SkillPromptEntry;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;
import fun.fengwk.kkstudio.share.ai.catalog.EnvironmentSupportDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * ModelRequestDebugService 契约：以草稿 model / environment 在单次不可变 snapshot 上投影共享规划结果、把确定性拒绝降级为稳定 code
 * 与安全空投影、只按 request head 前缀物化活动 Invocation 的冻结请求、按同名 Agent definition 补齐 subagent 配置，并且全程只读。
 */
class ModelRequestDebugServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID SESSION_ID = new UUID(0L, 100L);
  private static final UUID TURN_START_ID = new UUID(0L, 2L);
  private static final UUID USER_ENTRY_ID = new UUID(0L, 3L);
  private static final UUID ASSISTANT_ENTRY_ID = new UUID(0L, 4L);
  private static final UUID MISSING_ENTRY_ID = new UUID(0L, 99L);
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final EnvironmentId ENV_B =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final String CURRENT_COMMIT = "0123456789abcdef0123456789abcdef01234567";
  private static final String LOCAL_PATH = "/data/skills/test-package/dev/SKILL.md";
  private static final GoalSetting GOAL = new GoalSetting(new UUID(0L, 7L), "ship the release");

  /** 草稿 model / environment：与 branch settings 不同，用来证明顶层字段只来自本次请求。 */
  private static final ModelSelection DRAFT_MODEL =
      new ModelSelection("draft-provider", "draft-model", "draft-variant");

  private static final String DRAFT_ENV = "env-c";

  /** 目标 Agent 的持久化配置：subagents 里的 nested 只是声明，绝不递归解析。 */
  private static final String CODER_CONFIG_JSON =
      "{\"tools\":[\"read\"],\"skills\":[{\"packageName\":\"pkg\",\"name\":\"sk\"}],"
          + "\"subagents\":[\"nested\"],\"inheritParentEnvironment\":true}";

  /** required-nullable 字段在真实 NON_NULL 序列化配置下也必须显式发射。 */
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private final HarnessRuntime runtime = mock(HarnessRuntime.class);
  private final DatabaseTurnResolver turnResolver = mock(DatabaseTurnResolver.class);
  private final AgentDefinitionRepository agentDefinitionRepository =
      mock(AgentDefinitionRepository.class);
  // 使用真实 codec：canonical config JSON 的 wire 形状与“无凭据”由真实编码证明，而不是 mock 约定。
  private final AgentDefinitionConfigCodec agentConfigCodec =
      new AgentDefinitionConfigCodec(new ObjectMapper());

  private final ModelRequestDebugService service =
      new ModelRequestDebugService(
          runtime,
          turnResolver,
          agentDefinitionRepository,
          agentConfigCodec,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void projectsTheSharedPlanIntoTheStructuredContract() {
    ThreadSnapshot snapshot = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), any()))
        .thenReturn(successfulPlan());
    stubCoderDefinition();

    HarnessModelRequestDebugDTO debug =
        service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV);

    assertEquals("NEXT_REQUEST_PREVIEW", debug.getKind());
    assertEquals(NOW, debug.getGeneratedAt());
    // model / environment 只来自草稿选择；没有活动 Invocation 时冻结视图为 null。
    assertEquals("draft-provider", debug.getModel().getProviderName());
    assertEquals("draft-model", debug.getModel().getModelName());
    assertEquals("draft-variant", debug.getModel().getVariant());
    assertEquals(DRAFT_ENV, debug.getEnvironmentName());
    assertEquals("system instruction", debug.getSystemInstruction());
    assertNull(debug.getPlanningError());
    assertNull(debug.getFrozenInvocation());

    // 候选 Tool 保持共享规划的「SENT 在前、FILTERED 在后」顺序，并携带稳定原因与固定 requiredEnvironmentId。
    assertEquals(
        List.of("read", "bash"),
        debug.getTools().stream().map(HarnessModelRequestDebugDTO.ToolDTO::getName).toList());
    HarnessModelRequestDebugDTO.ToolDTO sent = debug.getTools().getFirst();
    assertEquals("SENT", sent.getState());
    assertNull(sent.getFilterReason());
    assertEquals(EnvironmentSupportDTO.OPTIONAL, sent.getEnvironmentSupport());
    assertNull(sent.getRequiredEnvironmentId());
    assertEquals("builtin:read", sent.getProvenance());
    assertEquals("{\"type\":\"object\"}", sent.getInputSchemaJson());
    HarnessModelRequestDebugDTO.ToolDTO filtered = debug.getTools().get(1);
    assertEquals("FILTERED", filtered.getState());
    assertEquals("ENVIRONMENT_NOT_SELECTED", filtered.getFilterReason());
    assertEquals(EnvironmentSupportDTO.REQUIRED, filtered.getEnvironmentSupport());
    assertEquals(ENV_B.toString(), filtered.getRequiredEnvironmentId());

    // Skill 交付事实与 promptXml：只复用 compose 的同一渲染入口，XML 不可能漂移。
    HarnessModelRequestDebugDTO.SkillDTO skill = debug.getSkills().getFirst();
    assertEquals("test-package", skill.getPackageName());
    assertEquals("dev", skill.getName());
    assertEquals("LOCAL", skill.getDelivery());
    assertEquals(CURRENT_COMMIT, skill.getCurrentCommit());
    assertEquals(CURRENT_COMMIT, skill.getInstalledCommit());
    assertEquals("observed-head", skill.getObservedHeadCommit());
    assertEquals(
        AgentPromptComposer.skillFragment(
            new SkillPromptEntry("dev", "dev description", LOCAL_PATH)),
        skill.getPromptXml());

    // subagent：binding 事实来自规划，配置声明按同名 definition 补齐且只陈述直接声明。
    HarnessModelRequestDebugDTO.SubagentDTO subagent = debug.getSubagents().getFirst();
    assertEquals("coder", subagent.getName());
    assertEquals("Codes solutions.", subagent.getDescription());
    assertEquals(List.of("read"), subagent.getTools());
    assertEquals(1, subagent.getSkills().size());
    assertEquals("pkg", subagent.getSkills().getFirst().getPackageName());
    assertEquals("sk", subagent.getSkills().getFirst().getName());
    assertEquals(List.of("nested"), subagent.getSubagents());
    assertEquals(CODER_CONFIG_JSON, subagent.getConfigurationJson());

    assertEquals("SHORT", debug.getCacheControl().getRetention());
    // key 直接就是 session UUID 文本：读取投影绝不哈希、也不重新派生 affinity。
    assertEquals(SESSION_ID.toString(), debug.getCacheControl().getKey());
  }

  /**
   * 测试意图：预览是单次不可变 snapshot 上的纯查询——只读取一次 Thread snapshot，把草稿 model / environment 合并进真实 base
   * settings（保留 Agent 与 Goal）后交给共享规划，不做任何写入或控制面调用，也不改动 EntryPath。
   */
  @Test
  void forwardsDraftSelectionToTheSharedPlannerWithoutMutatingThePath() {
    ThreadSnapshot snapshot = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), any()))
        .thenReturn(successfulPlan());
    stubCoderDefinition();

    service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV);

    verify(runtime).getThreadSnapshot(THREAD_ID);
    verifyNoMoreInteractions(runtime);

    ArgumentCaptor<BranchSettings> settingsCaptor = ArgumentCaptor.forClass(BranchSettings.class);
    verify(turnResolver)
        .planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), settingsCaptor.capture());
    // 只调用一次规划：不入队、不 CAS、不发布 Package、不触发 Daemon sync、不发请求。
    verifyNoMoreInteractions(turnResolver);

    BranchSettings draft = settingsCaptor.getValue();
    assertEquals(branchSettings().withModel(DRAFT_MODEL).withEnvironmentName(DRAFT_ENV), draft);
    assertEquals("assistant", draft.agentName());
    assertEquals(GOAL, draft.goal());
    // EntryPath 与真实 base settings 完全未被草稿覆写。
    assertEquals(branchSettings(), snapshot.entryPath().baseSettings());
    assertEquals("provider", snapshot.entryPath().baseSettings().model().providerName());
    assertEquals("env-b", snapshot.entryPath().baseSettings().environmentName());
  }

  /** 测试意图：environment 的 null（未选）/ 选中 / clear 只是逐次透传给同一 planner，服务本身不缓存或改写选择。 */
  @Test
  void forwardsEnvironmentSelectionPerCall() {
    ThreadSnapshot snapshot = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), any()))
        .thenReturn(successfulPlan());
    stubCoderDefinition();

    service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, null);
    service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV);
    service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, null);

    ArgumentCaptor<BranchSettings> settingsCaptor = ArgumentCaptor.forClass(BranchSettings.class);
    verify(turnResolver, times(3))
        .planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), settingsCaptor.capture());
    List<BranchSettings> drafts = settingsCaptor.getAllValues();
    assertNull(drafts.get(0).environmentName());
    assertEquals(DRAFT_ENV, drafts.get(1).environmentName());
    assertNull(drafts.get(2).environmentName());
  }

  /** 测试意图：确定性 planning 拒绝只投影稳定 code 与安全空投影，自由文本 detail 绝不进入对外投影。 */
  @Test
  void projectsDeterministicRejectionAsStableCodeAndSafeEmptyProjection() throws IOException {
    ThreadSnapshot snapshot = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), any()))
        .thenReturn(
            new LiveTurnPlan.Rejected(
                DatabaseTurnResolver.REJECTION_CODE, "agent not found: ghost"));

    HarnessModelRequestDebugDTO debug =
        service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV);

    assertEquals(DatabaseTurnResolver.REJECTION_CODE, debug.getPlanningError());
    assertEquals("", debug.getSystemInstruction());
    assertEquals(List.of(), debug.getTools());
    assertEquals(List.of(), debug.getSkills());
    assertEquals(List.of(), debug.getSubagents());
    assertNull(debug.getCacheControl());
    assertNull(debug.getFrozenInvocation());
    // 顶层草稿选择与生成时间不因 planning 失败而丢失。
    assertEquals("draft-provider", debug.getModel().getProviderName());
    assertEquals(DRAFT_ENV, debug.getEnvironmentName());
    // 规划失败时不读取任何 subagent definition，不静默伪造配置。
    verifyNoMoreInteractions(agentDefinitionRepository);

    String json = JSON.writeValueAsString(debug);
    assertTrue(
        json.contains("\"planningError\":\"" + DatabaseTurnResolver.REJECTION_CODE + "\""), json);
    // required-nullable 字段在 NON_NULL 配置下仍显式发射 null，客户端不会把「未返回」当成「无值」。
    assertTrue(json.contains("\"frozenInvocation\":null"), json);
    assertFalse(json.contains("agent not found"), json);
  }

  /**
   * 测试意图：活动 Invocation 的冻结请求必须物化在 root-to-requestHead 前缀上。Tool context 的当前 head 是 Assistant
   * 结果（request head 的后代），用当前 head 会把后续结果投影进请求，因此这里显式断言它不出现。
   */
  @Test
  void materializesFrozenInvocationAgainstTheRequestHeadPrefixInsteadOfTheToolContextHead() {
    ThreadSnapshot snapshot = toolContextSnapshot(USER_ENTRY_ID);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), any()))
        .thenReturn(successfulPlan());
    stubCoderDefinition();

    HarnessModelRequestDebugDTO.FrozenInvocationDTO frozen =
        service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV).getFrozenInvocation();

    assertEquals("FROZEN_INVOCATION", frozen.getKind());
    ProviderRequest materialized = new ProviderRequestJsonCodec().decode(frozen.getRequestJson());
    assertEquals("system instruction", materialized.systemInstruction());
    assertEquals(1, materialized.messages().size());
    assertEquals(
        "user-1",
        ((ProviderTextBlock) materialized.messages().getFirst().contents().getFirst()).text());
    assertFalse(frozen.getRequestJson().contains("assistant-reply"), frozen.getRequestJson());
  }

  /**
   * 测试意图：冻结事实只来自原始 snapshot 与原始 path——换草稿 model / environment 不改变冻结请求 JSON；预览被确定性拒绝时冻结事实 仍独立成立，没有活动
   * Model 时冻结视图缺席。
   */
  @Test
  void keepsFrozenInvocationStableAcrossDraftSelectionsAndWhenPreviewRejects() throws IOException {
    ThreadSnapshot snapshot = toolContextSnapshot(USER_ENTRY_ID);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), any()))
        .thenReturn(successfulPlan());
    stubCoderDefinition();

    String firstFrozen =
        service
            .getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV)
            .getFrozenInvocation()
            .getRequestJson();
    String secondFrozen =
        service
            .getModelRequestDebug(
                THREAD_ID,
                new ModelSelection("other-provider", "other-model", "other-variant"),
                null)
            .getFrozenInvocation()
            .getRequestJson();
    assertEquals(firstFrozen, secondFrozen);

    // 预览被拒绝：冻结事实仍然成立。
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), any()))
        .thenReturn(
            new LiveTurnPlan.Rejected(DatabaseTurnResolver.REJECTION_CODE, "agent not found"));
    HarnessModelRequestDebugDTO rejected =
        service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV);
    assertEquals(DatabaseTurnResolver.REJECTION_CODE, rejected.getPlanningError());
    assertEquals("FROZEN_INVOCATION", rejected.getFrozenInvocation().getKind());

    // 没有活动 Model：冻结视图必须缺席。
    ThreadSnapshot idle = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(idle);
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(idle.entryPath()), any()))
        .thenReturn(
            new LiveTurnPlan.Rejected(DatabaseTurnResolver.REJECTION_CODE, "agent not found"));
    assertNull(
        service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV).getFrozenInvocation());
  }

  /** 测试意图：subagent 配置只按同名 definition 直接读取一次，不递归解析 config.subagents 指向的下一层 Agent，也不暴露任何凭据。 */
  @Test
  void projectsSubagentConfigurationWithoutRecursionOrCredentials() {
    ThreadSnapshot snapshot = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), any()))
        .thenReturn(successfulPlan());
    stubCoderDefinition();

    HarnessModelRequestDebugDTO debug =
        service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV);

    HarnessModelRequestDebugDTO.SubagentDTO subagent = debug.getSubagents().getFirst();
    assertEquals(List.of("nested"), subagent.getSubagents());
    // nested 只是声明：绝不作为下一层 definition 被查询。
    verify(agentDefinitionRepository).getByName("coder");
    verifyNoMoreInteractions(agentDefinitionRepository);

    String configurationJson = subagent.getConfigurationJson();
    assertEquals(CODER_CONFIG_JSON, configurationJson);
    String lowered = configurationJson.toLowerCase();
    assertFalse(lowered.contains("key"), configurationJson);
    assertFalse(lowered.contains("token"), configurationJson);
    assertFalse(lowered.contains("secret"), configurationJson);
    assertFalse(lowered.contains("authorization"), configurationJson);
  }

  /** 测试意图：规划已保证 subagent 存在，Debug 侧同名缺失或配置非法必须明确失败，绝不静默返回空配置。 */
  @Test
  void rejectsMissingOrInvalidSubagentDefinitionInsteadOfReturningEmptyConfiguration() {
    ThreadSnapshot snapshot = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), any()))
        .thenReturn(successfulPlan());

    // 同名 definition 缺失。
    IllegalStateException missing =
        assertThrows(
            IllegalStateException.class,
            () -> service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV));
    assertTrue(
        missing.getMessage().contains("subagent definition not found: coder"),
        missing.getMessage());

    // 同名 definition 存在但配置非法。
    AgentDefinition invalid = new AgentDefinition();
    invalid.setName("coder");
    invalid.setConfigJson("{ not json");
    when(agentDefinitionRepository.getByName("coder")).thenReturn(invalid);
    IllegalStateException broken =
        assertThrows(
            IllegalStateException.class,
            () -> service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV));
    assertTrue(
        broken.getMessage().contains("subagent has an invalid configuration: coder"),
        broken.getMessage());
  }

  /** 测试意图：request head 不在当前 Thread 路径上说明 durable 不变量已破坏，必须 fail closed 而不是投影错误请求。 */
  @Test
  void failsClosedWhenTheFrozenRequestHeadIsNotOnTheThreadPath() {
    ThreadSnapshot snapshot = toolContextSnapshot(MISSING_ENTRY_ID);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), any()))
        .thenReturn(successfulPlan());
    stubCoderDefinition();

    assertThrows(
        IllegalStateException.class,
        () -> service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV));
  }

  /** 测试意图：基础设施/编程异常必须原样传播，绝不被误分类为 planning 失败。 */
  @Test
  void propagatesInfrastructureFailuresInsteadOfProjectingPlanningFailure() {
    ThreadSnapshot snapshot = idleSnapshot();
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);
    when(turnResolver.planLiveWithSettings(eq(THREAD_ID), eq(snapshot.entryPath()), any()))
        .thenThrow(new IllegalStateException("catalog is down"));

    assertThrows(
        IllegalStateException.class,
        () -> service.getModelRequestDebug(THREAD_ID, DRAFT_MODEL, DRAFT_ENV));
  }

  private void stubCoderDefinition() {
    AgentDefinition coder = new AgentDefinition();
    coder.setName("coder");
    coder.setConfigJson(CODER_CONFIG_JSON);
    when(agentDefinitionRepository.getByName("coder")).thenReturn(coder);
  }

  /**
   * 测试意图：旧的展示文本预览服务及其装配/测试必须彻底删除，Debug 只保留结构化投影这一条路径。这里扫描源码而不是 {@code Class.forName}，以免 target/
   * 下的陈旧 class 文件掩盖真实状态。
   */
  @Test
  void obsoleteTextPreviewServiceAndWiringAreGone() throws IOException {
    // 标记按片段拼接，避免本测试文件自身命中扫描。
    String obsoleteMarker = "SystemPrompt" + "Preview" + "Service";
    Path reactorRoot = reactorRoot();
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> paths =
        Stream.concat(
            Files.walk(reactorRoot.resolve("platform/src")),
            Files.walk(reactorRoot.resolve("web/src")))) {
      for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
        if (Files.readString(path, StandardCharsets.UTF_8).contains(obsoleteMarker)) {
          offenders.add(reactorRoot.relativize(path).toString());
        }
      }
    }
    assertTrue(offenders.isEmpty(), () -> "obsolete preview sources remain: " + offenders);
  }

  private static ThreadSnapshot idleSnapshot() {
    EntryPath path = basePath();
    return new ThreadSnapshot(
        thread(USER_ENTRY_ID), path, List.of(), null, List.of(), List.of(), List.of());
  }

  /** Tool context：head 是 Assistant 结果，而活动 Invocation 的 request head 是更早的 USER Entry。 */
  private static ThreadSnapshot toolContextSnapshot(UUID requestHeadEntryId) {
    EntryPath path = assistantHeadPath();
    ModelInvocation model =
        new ModelInvocation(
            new UUID(0L, 50L),
            THREAD_ID,
            TURN_START_ID,
            requestHeadEntryId,
            requestSpec(),
            ModelInvocationStatus.READY,
            0,
            null,
            null,
            null,
            null,
            List.of(),
            NOW,
            NOW);
    return new ThreadSnapshot(
        thread(ASSISTANT_ENTRY_ID), path, List.of(), model, List.of(), List.of(), List.of());
  }

  private static EntryPath basePath() {
    return new EntryPath(
        List.of(
            new Entry(SESSION_ID, SESSION_ID, null, new RootPayload(branchSettings()), NOW),
            new Entry(
                TURN_START_ID,
                SESSION_ID,
                SESSION_ID,
                new TurnStartPayload(TurnStartReason.INPUT, branchSettings(), THREAD_ID),
                NOW),
            new Entry(
                USER_ENTRY_ID,
                SESSION_ID,
                TURN_START_ID,
                message(AgentMessageRole.USER, "user-1"),
                NOW)));
  }

  private static EntryPath assistantHeadPath() {
    List<Entry> entries = new ArrayList<>(basePath().entries());
    entries.add(
        new Entry(
            ASSISTANT_ENTRY_ID,
            SESSION_ID,
            USER_ENTRY_ID,
            message(AgentMessageRole.ASSISTANT, "assistant-reply"),
            NOW));
    return new EntryPath(entries);
  }

  private static MessagePayload message(AgentMessageRole role, String text) {
    AgentMessage message = new AgentMessage(role, List.of(new TextMessageContent(text)));
    if (role == AgentMessageRole.ASSISTANT) {
      return new MessagePayload(
          message,
          new AssistantMessageMetadata(
              GenerationStopReason.COMPLETE, new ModelUsage(1, 1, 0, 0, 0, 0, 2), null),
          null);
    }
    return new MessagePayload(message, null, null);
  }

  private static ThreadState thread(UUID headEntryId) {
    return new ThreadState(
        THREAD_ID,
        SESSION_ID,
        null,
        headEntryId,
        CREATION_REQUEST_HASH,
        "thread",
        ThreadYoloPolicy.root(false),
        ThreadExecutionControl.RUNNABLE,
        0L,
        1L,
        0L,
        NOW,
        NOW);
  }

  private static BranchSettings branchSettings() {
    return new BranchSettings(
        "assistant", new ModelSelection("provider", "model", "default"), "env-b", GOAL);
  }

  /** 成功共享规划：SENT 候选在前、FILTERED 候选在后，附 Skill 交付事实、subagent 与 cache control。 */
  private static LiveTurnPlan.Planned successfulPlan() {
    return new LiveTurnPlan.Planned(
        requestSpec(),
        4096,
        List.of(
            new LiveTurnPlan.CandidateTool(
                "read",
                "Read a file.",
                "{\"type\":\"object\"}",
                EnvironmentSupport.OPTIONAL,
                null,
                "builtin:read",
                LiveTurnPlan.ToolState.SENT,
                null),
            new LiveTurnPlan.CandidateTool(
                "bash",
                "Run a command.",
                "{\"type\":\"object\"}",
                EnvironmentSupport.REQUIRED,
                ENV_B,
                "builtin:environment.bash",
                LiveTurnPlan.ToolState.FILTERED,
                LiveTurnPlan.FilterReason.ENVIRONMENT_NOT_SELECTED)),
        List.of(
            new LiveTurnPlan.PlannedSkill(
                "test-package",
                "dev",
                "dev description",
                LOCAL_PATH,
                SkillPromptResolution.Delivery.LOCAL,
                CURRENT_COMMIT,
                "observed-head",
                CURRENT_COMMIT)));
  }

  private static ModelRequestSpec requestSpec() {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 42L),
        new ModelDescriptor(
            "provider", "model", "wire-model", Set.of(ModelInputModality.TEXT), true, false),
        // 冻结请求只携带 Agent 正文唯一的 systemInstruction 与零 tool binding。
        new ModelVariant("default"),
        512,
        "system instruction",
        List.of(),
        List.of(new SubagentBinding("coder", "Codes solutions.")),
        new ProviderCacheControl(PromptCacheRetention.SHORT, SESSION_ID.toString()));
  }

  /** 从当前工作目录向上定位 reactor 根（同时含 platform 与 web 模块源码）。 */
  private static Path reactorRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("platform/src/main/java"))
          && Files.isDirectory(current.resolve("web/src/main/java"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("cannot locate reactor root for source scan");
  }
}

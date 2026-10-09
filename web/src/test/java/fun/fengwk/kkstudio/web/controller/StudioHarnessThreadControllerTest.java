package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.interaction.EnvironmentToolWait;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.harness.thread.query.ModelRequestDebugService;
import fun.fengwk.kkstudio.platform.harness.thread.query.UsageCostProjectionService;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;
import fun.fengwk.kkstudio.share.ai.catalog.EnvironmentSupportDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUsageCostDTO;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Harness Thread 控制/查询 API：snapshot availability、model request debug、rename、compact、yolo、stop 与
 * approval。
 */
class StudioHarnessThreadControllerTest {

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }

  private HarnessRuntime runtime;
  private ModelRequestDebugService modelRequestDebugService;
  private InteractionService interactionService;
  private UsageCostProjectionService usageCostProjectionService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    modelRequestDebugService = mock(ModelRequestDebugService.class);
    interactionService = mock(InteractionService.class);
    usageCostProjectionService = mock(UsageCostProjectionService.class);
    // 费用是快照的读取时投影：默认没有可计价事实，单个用例再按 entry id 指定。
    when(usageCostProjectionService.project(any())).thenReturn(Map.of());
    StudioHarnessThreadController controller =
        new StudioHarnessThreadController(
            runtime, modelRequestDebugService, interactionService, usageCostProjectionService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  /**
   * 意图：父 Thread 本地已静止时，快照的 status 只由该 Thread 自身投影为 IDLE 且 processing=false，不再递归子树表达忙碌；
   * 同时必须暴露执行父关系。该路由不依赖任何 task join 侧事实，因此服务端不额外注入任何委派状态。
   */
  @Test
  void snapshotProjectsIdleParentStatusAndParentThreadId() throws Exception {
    when(runtime.getThreadSnapshot(id(1)))
        .thenReturn(HarnessRuntimeTestFixtures.idleParentSnapshot(id(2)));
    when(runtime.manualCompactionAvailability(id(1)))
        .thenReturn(ManualCompactionAvailability.enabled());

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.thread.status").value("IDLE"))
        .andExpect(jsonPath("$.data.thread.processing").value(false))
        .andExpect(jsonPath("$.data.thread.parentThreadId").value(idText(2)));
  }

  /** 意图：快照的每个 Entry 都带读取时费用投影，未计价 Entry 显式为 null；投影只发生一次（不逐消息请求）。 */
  @Test
  void snapshotCarriesReadTimeUsageCostPerEntry() throws Exception {
    // ROOT -> TURN_START -> USER -> ASSISTANT -> TURN_END：费用只落在记录了用量的 ASSISTANT Entry 上。
    when(runtime.getThreadSnapshot(id(1)))
        .thenReturn(HarnessRuntimeTestFixtures.continuationPendingSnapshot(id(1)));
    when(runtime.manualCompactionAvailability(id(1)))
        .thenReturn(ManualCompactionAvailability.enabled());
    HarnessUsageCostDTO cost = new HarnessUsageCostDTO();
    cost.setCurrency("USD");
    cost.setAmount("0.500000000000");
    when(usageCostProjectionService.project(any())).thenReturn(Map.of(id(4), cost));

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.entries[0].usageCost").value(nullValue()))
        .andExpect(jsonPath("$.data.entries[3].entryId").value(idText(4)))
        .andExpect(jsonPath("$.data.entries[3].usageCost.currency").value("USD"))
        .andExpect(jsonPath("$.data.entries[3].usageCost.amount").value("0.500000000000"));

    // 一次快照只投影一次，绝不逐消息请求。
    verify(usageCostProjectionService, times(1)).project(any());
  }

  /** sidecar 一次批量回填所有调用；默认 HTTP mapper 的 ISO Instant 语义不需字段专属转换。 */
  @Test
  void snapshotBatchFillsEnvironmentNamesAndDeadlines() throws Exception {
    ModelInvocation model = mock(ModelInvocation.class);
    when(model.id()).thenReturn(id(10));
    when(model.threadId()).thenReturn(id(1));
    when(model.turnStartEntryId()).thenReturn(id(2));
    when(model.requestHeadEntryId()).thenReturn(id(3));
    when(model.resultEntryId()).thenReturn(id(4));
    when(model.status()).thenReturn(ModelInvocationStatus.SUCCEEDED);
    when(model.result()).thenReturn(HarnessRuntimeTestFixtures.toolCallResponse());
    ThreadSnapshot snapshot =
        new ThreadSnapshot(
            HarnessRuntimeTestFixtures.activeThread(id(1), id(4)),
            new EntryPath(
                List.of(
                    HarnessRuntimeTestFixtures.rootEntry(),
                        HarnessRuntimeTestFixtures.turnStartEntry(),
                    HarnessRuntimeTestFixtures.userMessageEntry(),
                        HarnessRuntimeTestFixtures.assistantEntry())),
            List.of(),
            model,
            List.of(
                HarnessRuntimeTestFixtures.waitingApprovalTool()
                    .decideApproval(
                        ToolApprovalDecision.ALLOWED,
                        id(300),
                        "test",
                        null,
                        Instant.parse("2026-10-08T10:00:00Z"),
                        Instant.parse("2026-10-08T10:00:00Z"))),
            List.of(),
            List.of());
    Instant deadline = Instant.parse("2026-10-08T12:00:00Z");
    when(runtime.getThreadSnapshot(id(1))).thenReturn(snapshot);
    when(runtime.manualCompactionAvailability(id(1)))
        .thenReturn(ManualCompactionAvailability.enabled());
    when(runtime.listEnvironmentToolWaits(any()))
        .thenReturn(
            List.of(
                new EnvironmentToolWait(
                    id(100), EnvironmentId.of(id(200)), false, "archlinux", deadline)));
    mockMvc
        .perform(get("/api/harness/threads/" + idText(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.toolInvocations[0].requiredEnvironmentId").value(idText(200)))
        .andExpect(jsonPath("$.data.toolInvocations[0].requiredEnvironmentName").value("archlinux"))
        .andExpect(
            jsonPath("$.data.toolInvocations[0].environmentWaitFreshnessAt")
                .value(deadline.toString()));
    verify(runtime, times(1)).listEnvironmentToolWaits(Set.of(id(100)));
  }

  /** 子节点入口返回真实根和父关系；根与未结束 outcome 必须显式序列化为 null。 */
  @Test
  void treeReturnsMinimalNodesAndExplicitNullableFields() throws Exception {
    when(runtime.getThreadTree(id(1)))
        .thenReturn(
            List.of(
                HarnessRuntimeTestFixtures.idleSnapshot(id(2)),
                HarnessRuntimeTestFixtures.idleParentSnapshot(id(2))));

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1) + "/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].threadId").value(idText(2)))
        .andExpect(jsonPath("$.data[0].parentThreadId").hasJsonPath())
        .andExpect(jsonPath("$.data[0].parentThreadId").value(nullValue()))
        .andExpect(jsonPath("$.data[0].outcome").hasJsonPath())
        .andExpect(jsonPath("$.data[0].outcome").value(nullValue()))
        .andExpect(jsonPath("$.data[0].model.providerName").value("openai"))
        .andExpect(jsonPath("$.data[0].model.modelName").value("gpt-5"))
        .andExpect(jsonPath("$.data[0].turnCount").value(0))
        .andExpect(jsonPath("$.data[0].toolCallCount").value(0))
        .andExpect(jsonPath("$.data[0].entries").doesNotExist())
        .andExpect(jsonPath("$.data[0].queuedCommands").doesNotExist())
        .andExpect(jsonPath("$.data[1].parentThreadId").value(idText(2)))
        .andExpect(jsonPath("$.data[1].status").value("IDLE"))
        .andExpect(jsonPath("$.data[1].processing").value(false));

    verify(runtime).getThreadTree(id(1));
    verify(runtime, never()).getThreadSnapshot(any());
    verifyNoInteractions(modelRequestDebugService, interactionService);
  }

  /** 非 canonical UUID 在进入 Runtime 前拒绝；未知节点沿用快照的 404 语义。 */
  @Test
  void treeRejectsInvalidUuidAndReturnsNotFoundForMissingThread() throws Exception {
    mockMvc.perform(get("/api/harness/threads/1-1-1-1-1/tree")).andExpect(status().isBadRequest());
    verifyNoInteractions(runtime);
    when(runtime.getThreadTree(id(999)))
        .thenThrow(
            new HarnessRuntimeNotFoundException("thread " + idText(999) + " does not exist"));

    mockMvc
        .perform(get("/api/harness/threads/" + idText(999) + "/tree"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors.detail").value("thread " + idText(999) + " does not exist"));
  }

  /** 意图：验证 GET /api/harness/threads/{threadId} 折叠快照查询路径并投影 manualCompaction 状态。 */
  @Test
  void snapshotProjectsManualCompactionAvailabilityFromHarnessRuntime() throws Exception {
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());
    when(runtime.manualCompactionAvailability(id(1)))
        .thenReturn(
            ManualCompactionAvailability.disabled(
                ManualCompactionAvailability.DisabledReason.BELOW_MINIMUM));

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("3"))
        .andExpect(jsonPath("$.data.thread.threadId").value(idText(1)))
        .andExpect(jsonPath("$.data.manualCompaction.available").value(false))
        .andExpect(jsonPath("$.data.manualCompaction.disabledReason").value("BELOW_MINIMUM"));
  }

  /**
   * 意图：验证 GET /api/harness/threads/{threadId}/model-request-debug 只读转发到结构化 Debug 服务并按 DTO 契约序列化
   * （required-nullable 字段显式发射 null），且绝不触碰 HarnessRuntime（无任何写入/控制面副作用）。
   */
  @Test
  void modelRequestDebugReturnsStructuredPreviewWithoutTouchingRuntime() throws Exception {
    when(modelRequestDebugService.getModelRequestDebug(id(1))).thenReturn(sampleDebug());

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1) + "/model-request-debug"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.kind").value("NEXT_REQUEST_PREVIEW"))
        .andExpect(jsonPath("$.data.model.providerName").value("provider"))
        .andExpect(jsonPath("$.data.model.modelName").value("model"))
        .andExpect(jsonPath("$.data.model.variant").value("default"))
        .andExpect(jsonPath("$.data.environmentName").value(nullValue()))
        .andExpect(jsonPath("$.data.systemInstruction").value("system instruction"))
        .andExpect(jsonPath("$.data.tools[0].name").value("read"))
        .andExpect(jsonPath("$.data.tools[0].state").value("SENT"))
        .andExpect(jsonPath("$.data.tools[0].environmentSupport").value("OPTIONAL"))
        .andExpect(jsonPath("$.data.tools[0].filterReason").value(nullValue()))
        .andExpect(jsonPath("$.data.tools[1].name").value("bash"))
        .andExpect(jsonPath("$.data.tools[1].state").value("FILTERED"))
        .andExpect(jsonPath("$.data.tools[1].filterReason").value("ENVIRONMENT_NOT_SELECTED"))
        .andExpect(jsonPath("$.data.skills[0].delivery").value("PLATFORM"))
        .andExpect(jsonPath("$.data.skills[0].observedHeadCommit").value(nullValue()))
        .andExpect(jsonPath("$.data.subagents[0].name").value("coder"))
        .andExpect(jsonPath("$.data.cacheControl.retention").value("NONE"))
        .andExpect(jsonPath("$.data.cacheControl.key").value(nullValue()))
        .andExpect(jsonPath("$.data.cacheControl.affinityKey").doesNotExist())
        .andExpect(jsonPath("$.data.planningError").value(nullValue()))
        .andExpect(jsonPath("$.data.frozenInvocation").value(nullValue()));

    // 活动 Invocation 的冻结视图原样透传：kind 与 canonical request JSON 按字符串承载。
    HarnessModelRequestDebugDTO frozen = sampleDebug();
    HarnessModelRequestDebugDTO.FrozenInvocationDTO frozenInvocation =
        new HarnessModelRequestDebugDTO.FrozenInvocationDTO();
    frozenInvocation.setKind("FROZEN_INVOCATION");
    frozenInvocation.setRequestJson("{\"model\":\"wire-model\"}");
    frozen.setFrozenInvocation(frozenInvocation);
    when(modelRequestDebugService.getModelRequestDebug(id(1))).thenReturn(frozen);

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1) + "/model-request-debug"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.frozenInvocation.kind").value("FROZEN_INVOCATION"))
        .andExpect(
            jsonPath("$.data.frozenInvocation.requestJson").value("{\"model\":\"wire-model\"}"));

    verify(modelRequestDebugService, times(2)).getModelRequestDebug(id(1));
    // Debug 是纯查询：Controller 不得调用任何 Runtime 控制面方法。
    verifyNoInteractions(runtime);
  }

  /** 意图：非 canonical threadId 在到达 Debug 服务前以 400 拒绝，不产生任何查询副作用。 */
  @Test
  void modelRequestDebugRejectsNonCanonicalThreadId() throws Exception {
    mockMvc
        .perform(get("/api/harness/threads/not-a-uuid/model-request-debug"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(modelRequestDebugService);
    verifyNoInteractions(runtime);
  }

  /** 意图：缺失 Thread 的 typed 异常保持 404 翻译，Debug 服务不额外发明错误语义。 */
  @Test
  void modelRequestDebugMapsMissingThreadToNotFound() throws Exception {
    when(modelRequestDebugService.getModelRequestDebug(id(1)))
        .thenThrow(new HarnessRuntimeNotFoundException("thread is missing"));

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1) + "/model-request-debug"))
        .andExpect(status().isNotFound());

    verifyNoInteractions(runtime);
  }

  /** 最小合法 Debug 投影：覆盖 required-nullable 与 SENT 在前、FILTERED 在后的候选顺序。 */
  private static HarnessModelRequestDebugDTO sampleDebug() {
    HarnessModelRequestDebugDTO.ToolDTO sent = new HarnessModelRequestDebugDTO.ToolDTO();
    sent.setName("read");
    sent.setDescription("Read a file.");
    sent.setInputSchemaJson("{\"type\":\"object\"}");
    sent.setEnvironmentSupport(EnvironmentSupportDTO.OPTIONAL);
    sent.setRequiredEnvironmentId(null);
    sent.setProvenance("builtin:read");
    sent.setState("SENT");
    sent.setFilterReason(null);

    HarnessModelRequestDebugDTO.ToolDTO filtered = new HarnessModelRequestDebugDTO.ToolDTO();
    filtered.setName("bash");
    filtered.setDescription("Run a command.");
    filtered.setInputSchemaJson("{\"type\":\"object\"}");
    filtered.setEnvironmentSupport(EnvironmentSupportDTO.REQUIRED);
    filtered.setRequiredEnvironmentId(null);
    filtered.setProvenance("builtin:bash");
    filtered.setState("FILTERED");
    filtered.setFilterReason("ENVIRONMENT_NOT_SELECTED");

    HarnessModelRequestDebugDTO.SkillDTO skill = new HarnessModelRequestDebugDTO.SkillDTO();
    skill.setPackageName("test-package");
    skill.setName("dev");
    skill.setDescription("dev description");
    skill.setPath("kkstudio:/skills/test-package/dev/SKILL.md");
    skill.setDelivery("PLATFORM");
    skill.setCurrentCommit("0123456789abcdef0123456789abcdef01234567");
    skill.setObservedHeadCommit(null);
    skill.setInstalledCommit(null);
    skill.setPromptXml("  <skill>");

    HarnessModelRequestDebugDTO.SubagentDTO subagent =
        new HarnessModelRequestDebugDTO.SubagentDTO();
    subagent.setName("coder");
    subagent.setDescription("Codes solutions.");

    HarnessModelRequestDebugDTO.CacheControlDTO cacheControl =
        new HarnessModelRequestDebugDTO.CacheControlDTO();
    cacheControl.setRetention("NONE");
    // NONE 时 key 显式 null：wire 上「未启用」与「有 key」必须可区分。
    cacheControl.setKey(null);

    HarnessModelRequestDebugDTO debug = new HarnessModelRequestDebugDTO();
    debug.setKind("NEXT_REQUEST_PREVIEW");
    debug.setGeneratedAt(Instant.parse("2026-08-17T00:00:00Z"));
    HarnessModelSelectionDTO model = new HarnessModelSelectionDTO();
    model.setProviderName("provider");
    model.setModelName("model");
    model.setVariant("default");
    debug.setModel(model);
    debug.setEnvironmentName(null);
    debug.setSystemInstruction("system instruction");
    debug.setTools(List.of(sent, filtered));
    debug.setSkills(List.of(skill));
    debug.setSubagents(List.of(subagent));
    debug.setCacheControl(cacheControl);
    debug.setPlanningError(null);
    debug.setFrozenInvocation(null);
    return debug;
  }

  /**
   * 意图：验证 POST /api/harness/threads/{threadId}/compact 调用受 version 保护的手动压缩并返回 HTTP 202 Accepted。
   */
  @Test
  void compactCallsInjectedHarnessRuntimeWithVersionFenceAndMapsCommitResult() throws Exception {
    when(runtime.compactThread(any(CompactThreadCommand.class)))
        .thenReturn(new CompactThreadResult(HarnessRuntimeTestFixtures.thread(id(1)), id(2), null));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            post("/api/harness/threads/" + idText(1) + "/compact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.thread.threadId").value(idText(1)))
        .andExpect(jsonPath("$.data.turnStartEntryId").value(idText(2)))
        .andExpect(jsonPath("$.data.childThreadId").value(nullValue()));

    ArgumentCaptor<CompactThreadCommand> captor =
        ArgumentCaptor.forClass(CompactThreadCommand.class);
    verify(runtime).compactThread(captor.capture());
    assertEquals(id(1), captor.getValue().threadId());
    assertEquals(3L, captor.getValue().expectedVersion());
  }

  /** 意图：验证手动压缩冲突时正确映射 MANUAL_COMPACTION_UNAVAILABLE 错误。 */
  @Test
  void compactConflictPreservesManualUnavailableReason() throws Exception {
    when(runtime.compactThread(any(CompactThreadCommand.class)))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.MANUAL_COMPACTION_UNAVAILABLE,
                "manual compaction is disabled"));

    mockMvc
        .perform(
            post("/api/harness/threads/" + idText(1) + "/compact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.reason").value("MANUAL_COMPACTION_UNAVAILABLE"));
  }

  /** 意图：验证手动压缩拒绝非字符串 version。 */
  @Test
  void compactRejectsNonStringVersionAtTheHttpBoundary() throws Exception {
    mockMvc
        .perform(
            post("/api/harness/threads/" + idText(1) + "/compact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3}"))
        .andExpect(status().isBadRequest());
    verify(runtime, never()).compactThread(any(CompactThreadCommand.class));
  }

  /** 名称冲突保留类型化 reason，并由既有异常边界映射为 409。 */
  @Test
  void renameNameConflictIsHttp409WithTypedReason() throws Exception {
    when(runtime.renameThread(any(RenameThreadCommand.class)))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.THREAD_NAME_CONFLICT, "name already used"));
    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"main\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.reason").value("THREAD_NAME_CONFLICT"));
  }

  /** 验证重命名成功后从权威 snapshot 回读 name/version。 */
  @Test
  void renameThreadCallsRuntimeAndReturnsCanonicalNameAndVersion() throws Exception {
    when(runtime.renameThread(any(RenameThreadCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.renamedThread(id(1)));
    when(runtime.getThreadSnapshot(id(1)))
        .thenReturn(HarnessRuntimeTestFixtures.renamedIdleSnapshot());

    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  new thread name  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.threadId").value(idText(1)))
        .andExpect(jsonPath("$.data.name").value("new thread name"))
        .andExpect(jsonPath("$.data.version").value("4"));

    ArgumentCaptor<RenameThreadCommand> captor = ArgumentCaptor.forClass(RenameThreadCommand.class);
    verify(runtime).renameThread(captor.capture());
    verify(runtime).getThreadSnapshot(id(1));
    assertEquals(id(1), captor.getValue().threadId());
    assertEquals("new thread name", captor.getValue().name());
  }

  /** 意图：验证 rename body 的严格边界在到达 Runtime 前被拒绝（缺失/显式 null/非字符串/blank/超长/未知字段/404）。 */
  @Test
  void renameThreadRejectsStrictBodyViolationsAndMapsMissingThreadToNotFound() throws Exception {
    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok\",\"unknown\":true}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":42}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":null}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \"}"))
        .andExpect(status().isBadRequest());
    verify(runtime, never()).renameThread(any(RenameThreadCommand.class));

    when(runtime.renameThread(any(RenameThreadCommand.class)))
        .thenThrow(new HarnessRuntimeNotFoundException("thread is missing"));
    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok\"}"))
        .andExpect(status().isNotFound());
  }

  /** 意图：超长 name 必须 400，且错误响应绝不回显用户提交的名称值（敏感数据不外泄）。 */
  @Test
  void renameThreadRejectsOverlongNameWithoutEchoingTheSubmittedValue() throws Exception {
    String overlongName = "OVERLONG-SECRET-" + "t".repeat(257);
    String response =
        mockMvc
            .perform(
                put("/api/harness/threads/" + idText(1) + "/name")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + overlongName + "\"}"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains(overlongName), "400 响应不得回显非法名称值");
    assertFalse(response.contains("OVERLONG-SECRET"), "400 响应不得回显敏感 marker");
    verify(runtime, never()).renameThread(any(RenameThreadCommand.class));
  }

  /** 意图：验证 PUT /api/harness/threads/{threadId}/yolo 直接更新 YOLO policy 并返回权威当前 Thread。 */
  @Test
  void yoloUpdatesPolicyAndReturnsCurrentThread() throws Exception {
    when(runtime.setThreadYolo(any(SetThreadYoloCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.thread(id(1)));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.threadId").value(idText(1)))
        .andExpect(jsonPath("$.data.status").value("IDLE"));

    ArgumentCaptor<SetThreadYoloCommand> captor =
        ArgumentCaptor.forClass(SetThreadYoloCommand.class);
    verify(runtime).setThreadYolo(captor.capture());
    assertTrue(captor.getValue().enabled());
  }

  /** 非布尔值、缺失策略与未知字段均在控制面变更之前拒绝。 */
  @Test
  void yoloRejectsMalformedPolicyRequestsBeforeRuntimeMutation() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"yoloEnabled\":null}",
            "{\"yoloEnabled\":\"true\"}",
            "{\"yoloEnabled\":1}",
            "{\"yoloEnabled\":true,\"expectedVersion\":\"0\"}")) {
      mockMvc
          .perform(
              put("/api/harness/threads/" + idText(1) + "/yolo")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(runtime);
  }

  /** 意图：缺失 Thread 保持既有 404 翻译，不发明新的错误语义。 */
  @Test
  void yoloKeepsNotFoundTranslationForMissingThread() throws Exception {
    when(runtime.setThreadYolo(any(SetThreadYoloCommand.class)))
        .thenThrow(new HarnessRuntimeNotFoundException("thread is missing"));

    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true}"))
        .andExpect(status().isNotFound());
  }

  /** 意图：非 canonical threadId 在 runtime 变更之前以 400 拒绝。 */
  @Test
  void yoloRejectsNonCanonicalThreadIdBeforeRuntimeMutation() throws Exception {
    mockMvc
        .perform(
            put("/api/harness/threads/not-a-uuid/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(runtime);
  }

  /**
   * 测试意图：自由输入/Goal/设置/YOLO/Stop/压缩/重命名等人工控制只允许指向执行根；子 Thread 一律 409 且不产生 Runtime
   * 变更，根目标照常放行。审批与问卷回答写回子调用是合法例外，不经本判定。
   */
  @Test
  void manualControlsRejectChildThreadAsConflictAndAllowExecutionRoot() throws Exception {
    UUID child = id(7);
    UUID root = id(1);
    when(runtime.findAncestorChain(child)).thenReturn(List.of(child, root));

    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(7) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok\"}"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            post("/api/harness/threads/" + idText(7) + "/compact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\"}"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(7) + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true}"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            post("/api/harness/threads/" + idText(7) + "/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopRequestId\":\"" + idText(9) + "\",\"expectedVersion\":\"3\"}"))
        .andExpect(status().isConflict());

    verify(runtime, never()).renameThread(any(RenameThreadCommand.class));
    verify(runtime, never()).compactThread(any(CompactThreadCommand.class));
    verify(runtime, never()).setThreadYolo(any(SetThreadYoloCommand.class));
    verify(runtime, never()).stop(any());

    // 根目标照常放行：findAncestorChain 返回单元素链。
    when(runtime.findAncestorChain(root)).thenReturn(List.of(root));
    when(runtime.setThreadYolo(any(SetThreadYoloCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.thread(root));
    when(runtime.getThreadSnapshot(root)).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true}"))
        .andExpect(status().isOk());
  }

  /** 意图：非 canonical threadId 在 runtime 变更之前以 400 拒绝。 */
  @Test
  void compactRejectsNonCanonicalThreadIdBeforeRuntimeMutation() throws Exception {
    mockMvc
        .perform(
            post("/api/harness/threads/not-a-uuid/compact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(runtime);
  }

  /** 测试意图：审批身份只能来自服务端认证主体。请求体携带 actor（无论伪造与否）都是未知字段 → 400，且绝不触达交互服务。 */
  @Test
  void approvalRejectsForgedActorFieldWithoutTouchingService() throws Exception {
    mockMvc
        .perform(
            put("/api/harness/threads/"
                    + idText(1)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"ALLOW\",\"decisionId\":\""
                        + idText(1)
                        + "\",\"actor\":\"admin\"}")
                .principal(() -> "alice"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(interactionService);
    verifyNoInteractions(runtime);
  }

  /**
   * 测试意图：审批请求体只接受 {decision, decisionId, reason}，未知字段拒绝与合法请求成功必须成对验证——携带 reason 的合法 请求必须成功并把 reason
   * 原样交给交互服务，而多带一个未知字段（非 actor 的任意字段同样如此）的请求必须在 DTO 的 @JsonAnySetter 处 400，detail
   * 精确回传拒绝原因，且不再触达交互服务。
   */
  @Test
  void approvalRejectsUnknownNonActorFieldWhileValidPayloadStillSucceeds() throws Exception {
    when(interactionService.decideApproval(any(ToolApprovalCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.waitingApprovalTool());

    mockMvc
        .perform(
            put("/api/harness/threads/"
                    + idText(1)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"DENY\",\"decisionId\":\""
                        + idText(1)
                        + "\",\"reason\":\"denied\"}")
                .principal(() -> "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"));

    mockMvc
        .perform(
            put("/api/harness/threads/"
                    + idText(1)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"DENY\",\"decisionId\":\""
                        + idText(1)
                        + "\",\"reason\":\"denied\",\"operator\":\"admin\"}")
                .principal(() -> "alice"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("unknown tool approval field: operator"));

    ArgumentCaptor<ToolApprovalCommand> captor = ArgumentCaptor.forClass(ToolApprovalCommand.class);
    verify(interactionService, times(1)).decideApproval(captor.capture());
    assertEquals(ToolApprovalDecision.DENIED, captor.getValue().decision());
    assertEquals("denied", captor.getValue().reason());
    verifyNoInteractions(runtime);
  }

  /**
   * 测试意图：decision/decisionId 的畸形值不得被 wire 层悄悄消化或落到交互服务：非字符串 JSON token、非 ALLOW/DENY 的 decision 文本、非
   * canonical UUID 的 decisionId 都必须在触达交互服务之前 400。
   */
  @Test
  void approvalRejectsMalformedDecisionAndDecisionIdWithoutTouchingService() throws Exception {
    for (String body :
        List.of(
            "{\"decision\":42,\"decisionId\":\"" + idText(1) + "\"}",
            "{\"decision\":\"ALLOW\",\"decisionId\":1}",
            "{\"decision\":\"MAYBE\",\"decisionId\":\"" + idText(1) + "\"}",
            "{\"decision\":\"ALLOW\",\"decisionId\":\"not-a-uuid\"}",
            "{\"decision\":\"ALLOW\",\"decisionId\":null}")) {
      mockMvc
          .perform(
              put("/api/harness/threads/"
                      + idText(1)
                      + "/tool-invocations/"
                      + idText(100)
                      + "/approval")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .principal(() -> "alice"))
          .andExpect(status().isBadRequest());
    }

    verifyNoInteractions(interactionService);
    verifyNoInteractions(runtime);
  }

  /** 意图：验证 POST stop 与 PUT tool approval 路径与方法映射正常工作。 */
  @Test
  void stopAndApprovalRoutesRemainAvailable() throws Exception {
    when(runtime.stop(any()))
        .thenReturn(
            new StopResult(
                false,
                HarnessRuntimeTestFixtures.thread(id(1)),
                List.of(
                    new StoppedThreadReceipt(
                        id(1),
                        id(9),
                        id(6),
                        2,
                        List.of(
                            new CancelledThreadInput(
                                1L,
                                id(50),
                                new UserMessageCommandPayload(AgentMessage.user("hello"))))))));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());
    mockMvc
        .perform(
            post("/api/harness/threads/" + idText(1) + "/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopRequestId\":\"" + idText(9) + "\",\"expectedVersion\":\"3\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("STOPPED"))
        .andExpect(jsonPath("$.data.stoppedThreads[0].threadId").value(idText(1)))
        .andExpect(jsonPath("$.data.stoppedThreads[0].stopRequestId").value(idText(9)))
        .andExpect(jsonPath("$.data.stoppedThreads[0].stoppedTurnEndEntryId").value(idText(6)))
        .andExpect(jsonPath("$.data.stoppedThreads[0].cancelledCommandCount").value(2))
        .andExpect(jsonPath("$.data.stoppedThreads[0].cancelledInputs[0].sequence").value("1"))
        .andExpect(
            jsonPath("$.data.stoppedThreads[0].cancelledInputs[0].idempotencyKey")
                .value(idText(50)))
        .andExpect(
            jsonPath("$.data.stoppedThreads[0].cancelledInputs[0].type").value("USER_MESSAGE"))
        .andExpect(
            jsonPath("$.data.stoppedThreads[0].cancelledInputs[0].payloadJson")
                .value(
                    "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hello\"}]}}"));

    when(interactionService.decideApproval(any(ToolApprovalCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.waitingApprovalTool());
    mockMvc
        .perform(
            put("/api/harness/threads/"
                    + idText(1)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"ALLOW\",\"decisionId\":\"" + idText(1) + "\"}")
                .principal(() -> "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"));

    ArgumentCaptor<ToolApprovalCommand> captor = ArgumentCaptor.forClass(ToolApprovalCommand.class);
    verify(interactionService).decideApproval(captor.capture());
    assertEquals(ToolApprovalDecision.ALLOWED, captor.getValue().decision());
    // 审批 actor 只能来自认证主体，请求体不再携带身份。
    assertEquals("alice", captor.getValue().actor());
  }

  /**
   * 意图：审批路由必须直达被请求 Thread 自己的 invocation——即使该 Thread 带执行父关系（异步 task 的子执行），也不得因为 "task join
   * 当前是否存在或是否已交付"而改变到达的 Thread/调用身份；换言之 approval 与孩子查询都不是 join 的附属状态。
   */
  @Test
  void approvalRouteOnChildThreadTargetsThatThreadInvocation() throws Exception {
    UUID childThreadId = id(7);
    UUID toolInvocationId = id(100);
    when(interactionService.decideApproval(any(ToolApprovalCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.waitingApprovalTool());

    mockMvc
        .perform(
            put("/api/harness/threads/"
                    + idText(7)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"ALLOW\",\"decisionId\":\"" + idText(51) + "\"}")
                .principal(() -> "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"));

    ArgumentCaptor<ToolApprovalCommand> captor = ArgumentCaptor.forClass(ToolApprovalCommand.class);
    verify(interactionService).decideApproval(captor.capture());
    assertEquals(childThreadId, captor.getValue().threadId());
    assertEquals(toolInvocationId, captor.getValue().toolInvocationId());
    assertEquals(ToolApprovalDecision.ALLOWED, captor.getValue().decision());
    assertEquals("alice", captor.getValue().actor());
  }
}

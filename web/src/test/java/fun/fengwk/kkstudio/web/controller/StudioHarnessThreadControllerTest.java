package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import fun.fengwk.kkstudio.harness.runtime.CancelledUserMessage;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.platform.harness.thread.query.ModelRequestDebugService;
import fun.fengwk.kkstudio.platform.project.tool.ProjectThreadOwnerResolver;
import fun.fengwk.kkstudio.share.ai.catalog.EnvironmentSupportDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.time.Instant;
import java.util.List;
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
  private ProjectThreadOwnerResolver projectThreadOwnerResolver;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    modelRequestDebugService = mock(ModelRequestDebugService.class);
    projectThreadOwnerResolver = mock(ProjectThreadOwnerResolver.class);
    StudioHarnessThreadController controller =
        new StudioHarnessThreadController(
            runtime, modelRequestDebugService, projectThreadOwnerResolver);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
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
        .andExpect(jsonPath("$.data.cacheControl.affinityKey").value(nullValue()))
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
    cacheControl.setAffinityKey(null);
    cacheControl.setBreakpoints(List.of());

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
        .andExpect(jsonPath("$.data.modelInvocationId").value(nullValue()));

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

  /**
   * 意图：验证 PUT /api/harness/threads/{threadId}/name 执行 Runtime 重命名，并从重命名后权威 snapshot 回读
   * name/version。
   */
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

  /** 意图：验证 PUT /api/harness/threads/{threadId}/yolo 对非 Issue 归属 Thread 执行 CAS 更新并返回权威当前 Thread。 */
  @Test
  void yoloCarriesVersionCasAndReturnsCurrentThread() throws Exception {
    when(projectThreadOwnerResolver.isIssueAgentBranch(id(1))).thenReturn(false);
    when(runtime.setThreadYolo(any(SetThreadYoloCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.thread(id(1)));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\",\"yoloEnabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.threadId").value(idText(1)))
        .andExpect(jsonPath("$.data.status").value("IDLE"));

    ArgumentCaptor<SetThreadYoloCommand> captor =
        ArgumentCaptor.forClass(SetThreadYoloCommand.class);
    verify(runtime).setThreadYolo(captor.capture());
    verify(projectThreadOwnerResolver).isIssueAgentBranch(id(1));
    assertEquals(3L, captor.getValue().expectedVersion());
  }

  /**
   * 意图：Issue Agent Branch（含无活动 Run 的 idle 情形）的公开 YOLO 覆盖必须在任何 runtime 变更前以 409 拒绝，既不调用 CAS 也不读取快照。
   */
  @Test
  void yoloRejectsIssueAgentBranchWithoutTouchingRuntime() throws Exception {
    when(projectThreadOwnerResolver.isIssueAgentBranch(id(1))).thenReturn(true);

    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\",\"yoloEnabled\":true}"))
        .andExpect(status().isConflict());

    // 拒绝必须发生在 runtime 变更之前：归属检查不依赖 Run 状态，也不允许经 CAS 绕过 Project 策略。
    verifyNoInteractions(runtime);
  }

  /** 意图：缺失 Thread（resolver 判定为 false）保持既有 404 翻译，不发明新的错误语义。 */
  @Test
  void yoloKeepsNotFoundTranslationForMissingThread() throws Exception {
    when(projectThreadOwnerResolver.isIssueAgentBranch(id(1))).thenReturn(false);
    when(runtime.setThreadYolo(any(SetThreadYoloCommand.class)))
        .thenThrow(new HarnessRuntimeNotFoundException("thread is missing"));

    mockMvc
        .perform(
            put("/api/harness/threads/" + idText(1) + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\",\"yoloEnabled\":true}"))
        .andExpect(status().isNotFound());
  }

  /** 意图：非 canonical threadId 在归属判定与 runtime 变更之前以 400 拒绝（Issue Agent Branch 判定不得先于形状校验）。 */
  @Test
  void yoloRejectsNonCanonicalThreadIdBeforeOwnershipCheck() throws Exception {
    mockMvc
        .perform(
            put("/api/harness/threads/not-a-uuid/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\",\"yoloEnabled\":true}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(projectThreadOwnerResolver);
    verifyNoInteractions(runtime);
  }

  @Test
  void stopRejectsIssueAgentBranchWithoutTouchingRuntime() throws Exception {
    // 测试意图：Issue Agent 的停止须先经过 Issue 工作流，通用 stop 不得直接撤销其 Turn 或命令。
    when(projectThreadOwnerResolver.isIssueAgentBranch(id(1))).thenReturn(true);
    mockMvc
        .perform(
            post("/api/harness/threads/" + idText(1) + "/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopRequestId\":\"" + idText(9) + "\",\"expectedVersion\":\"3\"}"))
        .andExpect(status().isConflict());
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
                id(9),
                2,
                List.of(
                    new CancelledUserMessage(
                        1L, id(50), List.of(new TextMessageContent("hello"))))));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());
    mockMvc
        .perform(
            post("/api/harness/threads/" + idText(1) + "/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopRequestId\":\"" + idText(9) + "\",\"expectedVersion\":\"3\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("STOPPED"))
        .andExpect(jsonPath("$.data.cancelledCommandCount").value(2))
        .andExpect(jsonPath("$.data.cancelledUserMessages[0].sequence").value("1"))
        .andExpect(jsonPath("$.data.cancelledUserMessages[0].idempotencyKey").value(idText(50)))
        .andExpect(
            jsonPath("$.data.cancelledUserMessages[0].messageJson")
                .value(
                    "{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hello\"}]}"));

    when(runtime.decideToolApproval(any(ToolApprovalCommand.class)))
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
                    "{\"decision\":\"ALLOW\",\"decisionId\":\""
                        + idText(1)
                        + "\",\"actor\":\"alice\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"));

    ArgumentCaptor<ToolApprovalCommand> captor = ArgumentCaptor.forClass(ToolApprovalCommand.class);
    verify(runtime).decideToolApproval(captor.capture());
    assertEquals(ToolApprovalDecision.ALLOWED, captor.getValue().decision());
  }
}

package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.util.List;
import java.util.UUID;

/** Harness Thread 控制/查询 API：snapshot availability、rename、compact、yolo、stop 与 approval。 */
class StudioHarnessThreadControllerTest {

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }

  private HarnessRuntime runtime;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    StudioHarnessThreadController controller = new StudioHarnessThreadController(runtime);
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

  /** 意图：验证 PUT /api/harness/threads/{threadId}/yolo 执行 CAS 更新并返回权威当前 Thread。 */
  @Test
  void yoloCarriesVersionCasAndReturnsCurrentThread() throws Exception {
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
    assertEquals(3L, captor.getValue().expectedVersion());
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

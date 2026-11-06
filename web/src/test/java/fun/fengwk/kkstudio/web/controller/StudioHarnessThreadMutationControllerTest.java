package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.api.CompactThreadResult;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.api.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.thread.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.api.thread.SetThreadYoloCommand;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Thread 人工写端点：abandon、compact、rename、yolo、stop 与执行根守卫。 */
class StudioHarnessThreadMutationControllerTest extends HarnessThreadControllerTestSupport {

  /** 意图：放弃执行确认严格解析字符串版本并返回最新快照，不限制目标必须为执行根。 */
  @Test
  void abandonExecutionMapsVersionAndReturnsSnapshotForChildThread() throws Exception {
    when(runtime.abandonExecution(id(7), id(9), 4L))
        .thenReturn(HarnessRuntimeTestFixtures.idleSnapshot(id(7)));

    mockMvc
        .perform(
            post("/api/threads/" + idText(7) + "/executions/" + idText(9) + "/abandon")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"4\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.thread.threadId").value(idText(7)))
        .andExpect(jsonPath("$.data.version").value("3"));

    verify(runtime).abandonExecution(id(7), id(9), 4L);
  }

  /** 意图：非字符串/非规范版本与非 canonical UUID 在调用 Runtime 前以 400 拒绝。 */
  @Test
  void abandonExecutionRejectsMalformedRequestBeforeRuntime() throws Exception {
    for (String body :
        List.of(
            "{\"expectedVersion\":4}",
            "{\"expectedVersion\":\"-1\"}",
            "{\"expectedVersion\":\"4.0\"}",
            "{\"expectedVersion\":\"4\",\"unknown\":true}")) {
      mockMvc
          .perform(
              post("/api/threads/" + idText(7) + "/executions/" + idText(9) + "/abandon")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    mockMvc
        .perform(
            post("/api/threads/not-a-uuid/executions/" + idText(9) + "/abandon")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"4\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(runtime, modelRequestDebugService, interactionService);
  }

  /** 意图：验证 POST /api/threads/{threadId}/compact 调用受 version 保护的手动压缩并返回 HTTP 202 Accepted。 */
  @Test
  void compactCallsInjectedHarnessRuntimeWithVersionFenceAndMapsCommitResult() throws Exception {
    when(runtime.compactThread(any(CompactThreadCommand.class)))
        .thenReturn(new CompactThreadResult(HarnessRuntimeTestFixtures.thread(id(1)), id(2), null));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            post("/api/threads/" + idText(1) + "/compact")
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
            DomainException.conflict(
                "MANUAL_COMPACTION_UNAVAILABLE", "manual compaction is disabled"));

    mockMvc
        .perform(
            post("/api/threads/" + idText(1) + "/compact")
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
            post("/api/threads/" + idText(1) + "/compact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3}"))
        .andExpect(status().isBadRequest());
    verify(runtime, never()).compactThread(any(CompactThreadCommand.class));
  }

  /** 意图：非 canonical threadId 在 runtime 变更之前以 400 拒绝。 */
  @Test
  void compactRejectsNonCanonicalThreadIdBeforeRuntimeMutation() throws Exception {
    mockMvc
        .perform(
            post("/api/threads/not-a-uuid/compact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(runtime);
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
            put("/api/threads/" + idText(1) + "/name")
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
            put("/api/threads/" + idText(1) + "/name")
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
            put("/api/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok\",\"unknown\":true}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":42}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":null}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/threads/" + idText(1) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \"}"))
        .andExpect(status().isBadRequest());
    verify(runtime, never()).renameThread(any(RenameThreadCommand.class));

    when(runtime.renameThread(any(RenameThreadCommand.class)))
        .thenThrow(new HarnessRuntimeNotFoundException("thread is missing"));
    mockMvc
        .perform(
            put("/api/threads/" + idText(1) + "/name")
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
                put("/api/threads/" + idText(1) + "/name")
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

  /** 意图：验证 PUT /api/threads/{threadId}/yolo 直接更新 YOLO policy 并返回权威当前 Thread。 */
  @Test
  void yoloUpdatesPolicyAndReturnsCurrentThread() throws Exception {
    when(runtime.setThreadYolo(any(SetThreadYoloCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.thread(id(1)));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            put("/api/threads/" + idText(1) + "/yolo")
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
              put("/api/threads/" + idText(1) + "/yolo")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(runtime);
  }

  /** 意图：非 canonical threadId 在 runtime 变更之前以 400 拒绝。 */
  @Test
  void yoloRejectsNonCanonicalThreadIdBeforeRuntimeMutation() throws Exception {
    mockMvc
        .perform(
            put("/api/threads/not-a-uuid/yolo")
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
    when(runtime.findRootThreadId(child)).thenReturn(Optional.of(root));

    mockMvc
        .perform(
            put("/api/threads/" + idText(7) + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok\"}"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            post("/api/threads/" + idText(7) + "/compact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\"}"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            put("/api/threads/" + idText(7) + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true}"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            post("/api/threads/" + idText(7) + "/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopRequestId\":\"" + idText(9) + "\",\"expectedVersion\":\"3\"}"))
        .andExpect(status().isConflict());

    verify(runtime, never()).renameThread(any(RenameThreadCommand.class));
    verify(runtime, never()).compactThread(any(CompactThreadCommand.class));
    verify(runtime, never()).setThreadYolo(any(SetThreadYoloCommand.class));
    verify(runtime, never()).stop(any());

    // 根目标照常放行：执行根即自身。
    when(runtime.findRootThreadId(root)).thenReturn(Optional.of(root));
    when(runtime.setThreadYolo(any(SetThreadYoloCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.thread(root));
    when(runtime.getThreadSnapshot(root)).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            put("/api/threads/" + idText(1) + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true}"))
        .andExpect(status().isOk());
  }

  /** 意图：验证 POST stop 返回聚合回执与可恢复草稿，且回读权威快照。 */
  @Test
  void stopReturnsAggregatedReceiptAndCancelledInputs() throws Exception {
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
            post("/api/threads/" + idText(1) + "/stop")
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
  }
}

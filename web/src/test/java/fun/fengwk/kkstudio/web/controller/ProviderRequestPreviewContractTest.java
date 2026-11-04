package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.api.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ProviderRequestPreviewUnavailableException;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ProviderRequestPreviewUnavailableException.Reason;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO;
import fun.fengwk.kkstudio.web.advice.ApiResponseStatusAdvice;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 请求预览两个入口共享的 HTTP 契约：成功只暴露协议请求体本身，拒绝按 400/404/409 确定性翻译，且响应绝不泄露 credential、endpoint 或异常 cause。
 *
 * <p>Thread 作用域与 Session 作用域只在「事实边界」上不同；形状、命令顺序与错误语义必须保持同一套断言。各自独有的入口字段和 历史重建语义由子类补充。
 */
abstract class ProviderRequestPreviewContractTest {

  protected static final String SET_EXECUTION_KEY = "00000000-0000-0000-0000-000000000004";
  protected static final String USER_MESSAGE_KEY = "00000000-0000-0000-0000-000000000005";

  protected ProviderRequestPreviewService previewService;
  protected MockMvc mockMvc;

  protected abstract Object controller();

  /** 使用合法请求体执行一次主预览入口（Thread 或 Session 草稿）。 */
  protected abstract ResultActions performPreview() throws Exception;

  protected abstract void stubPreviewSuccess(HarnessProviderRequestPreviewDTO dto);

  protected abstract void stubPreviewUnavailable(ProviderRequestPreviewUnavailableException error);

  protected abstract void stubPreviewFailure(RuntimeException error);

  /** 执行主入口并返回收到的有序命令，用于共享命令映射断言。 */
  protected abstract List<NewThreadCommand> captureCommands() throws Exception;

  @BeforeEach
  void setUpContract() {
    previewService = mock(ProviderRequestPreviewService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller())
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ApiResponseStatusAdvice())
            .build();
  }

  /** 成功响应逐字段给出协议请求体与快照事实，且绝不出现 credential / endpoint / 认证 Header。 */
  @Test
  void successReturnsEncodedBodyWithoutAnyCredentialOrEndpoint() throws Exception {
    String body = "{\"model\":\"acceptance-stub\",\"messages\":[{\"role\":\"user\"}]}";
    HarnessProviderRequestPreviewDTO dto =
        previewDto(
            HarnessProviderRequestPreviewDTO.DRAFT_REQUEST_PREVIEW,
            HarnessProviderRequestPreviewDTO.DRAFT_NOTICE,
            sourceHeadEntryId(),
            body);
    stubPreviewSuccess(dto);

    String response =
        performPreview()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kind").value("DRAFT_REQUEST_PREVIEW"))
            .andExpect(jsonPath("$.data.providerType").value("OPENAI"))
            .andExpect(jsonPath("$.data.modelName").value("acceptance-stub"))
            .andExpect(jsonPath("$.data.bodyByteSize").value(dto.getBodyByteSize()))
            .andExpect(jsonPath("$.data.bodyJson").value(body))
            .andExpect(jsonPath("$.data.sourceHeadEntryId").value(sourceHeadEntryId()))
            .andExpect(jsonPath("$.data.generatedAt").value("2026-01-01T00:00:00Z"))
            .andExpect(
                jsonPath("$.data.notice").value(HarnessProviderRequestPreviewDTO.DRAFT_NOTICE))
            .andExpect(jsonPath("$.data.snapshotNotice").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertFalse(response.contains("secret"), "响应不得包含任何凭据");
    assertFalse(response.contains("http://"), "响应不得包含任何 endpoint");
    assertFalse(response.toLowerCase().contains("authorization"), "响应不得包含认证 Header");
  }

  /** 九类拒绝在实际 MVC 序列化后保留 reason，但内部 cause/connection 详情不得成为响应字段。 */
  @ParameterizedTest
  @EnumSource(Reason.class)
  void rejectsEveryTypedReasonWithoutCauseDetails(Reason reason) throws Exception {
    stubPreviewUnavailable(
        new ProviderRequestPreviewUnavailableException(
            reason, "safe preview detail", new IllegalStateException("private connection detail")));

    String response =
        performPreview()
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errors.reason").value(reason.name()))
            .andExpect(jsonPath("$.errors.detail").value("safe preview detail"))
            .andExpect(jsonPath("$.errors.cause").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains("private connection detail"));
  }

  /** 缺失目标与其它运行时查询一致是 404（不因为「预览」而变成 409 或 200）。 */
  @Test
  void translatesMissingTargetToNotFound() throws Exception {
    stubPreviewFailure(new HarnessRuntimeNotFoundException("target not found"));

    performPreview()
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors.detail").value("target not found"));
  }

  /** 服务自身的请求边界拒绝经统一翻译为 400，并保留安全 detail。 */
  @Test
  void translatesServiceRejectionToBadRequest() throws Exception {
    stubPreviewFailure(new IllegalArgumentException("preview shape is not acceptable"));

    performPreview()
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("preview shape is not acceptable"));
  }

  /** 两个入口共用同一命令映射：SET_EXECUTION 前缀 + 一条 USER_MESSAGE，且携带 canonical hash 与客户端幂等键。 */
  @Test
  void mapsSetExecutionThenUserMessageWithCanonicalIdentity() throws Exception {
    List<NewThreadCommand> commands = captureCommands();

    assertEquals(
        List.of(ThreadCommandType.SET_EXECUTION, ThreadCommandType.USER_MESSAGE),
        commands.stream().map(command -> command.payload().type()).toList());
    SetExecutionCommandPayload setExecution =
        assertInstanceOf(SetExecutionCommandPayload.class, commands.getFirst().payload());
    assertEquals("builtin", setExecution.execution().driver());
    assertEquals(
        new ModelSelection("acceptance-stub", "default"),
        BuiltinExecution.model(setExecution.execution()));
    assertTrue(
        commands.getFirst().requestHash().matches("[0-9a-f]{64}"),
        "SET_EXECUTION 必须携带 canonical request hash");
    assertEquals(UUID.fromString(SET_EXECUTION_KEY), commands.getFirst().idempotencyKey());
    assertEquals(UUID.fromString(USER_MESSAGE_KEY), commands.get(1).idempotencyKey());
  }

  protected abstract String sourceHeadEntryId();

  protected static HarnessProviderRequestPreviewDTO previewDto(
      String kind, String notice, String sourceHeadEntryId, String body) {
    HarnessProviderRequestPreviewDTO dto = new HarnessProviderRequestPreviewDTO();
    dto.setKind(kind);
    dto.setNotice(notice);
    dto.setGeneratedAt(Instant.parse("2026-01-01T00:00:00Z"));
    dto.setProviderType("OPENAI");
    dto.setModelName("acceptance-stub");
    dto.setBodyByteSize(body.getBytes(StandardCharsets.UTF_8).length);
    dto.setBodyJson(body);
    dto.setSourceHeadEntryId(sourceHeadEntryId);
    return dto;
  }

  /** 两个入口的公共请求体片段：TEXT-only USER_MESSAGE，独有内容由子类追加。 */
  protected static String setExecutionCommand() {
    return """
        {
          "type":"SET_EXECUTION",
          "idempotencyKey":"%s",
          "execution":{"driver":"builtin","settings":{"modelName":"acceptance-stub","variant":"default"}}
        }
        """
        .formatted(SET_EXECUTION_KEY);
  }

  protected static String textUserMessageCommand() {
    return """
        {
          "type":"USER_MESSAGE",
          "idempotencyKey":"%s",
          "contents":[{"type":"TEXT","text":"hello"}]
        }
        """
        .formatted(USER_MESSAGE_KEY);
  }
}

package fun.fengwk.kkstudio.harness.runtime.history;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderToolCallDiagnosticJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** HistoryPayloadMapper 纯映射测试：ASSISTANT / ASSISTANT_ERROR / TOOL / synthetic 四种 payload 的精确结构。 */
class HistoryPayloadMapperTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final HistoryPayloadMapper MAPPER = new HistoryPayloadMapper();

  @Test
  void assistantPayloadOrdersThinkingTextThenToolCalls() {
    ProviderResponse response =
        new ProviderResponse(
            "final text",
            "thinking here",
            List.of(
                new ProviderToolCall("call-1", "bash", "{}"),
                new ProviderToolCall("call-2", "grep", "{}")),
            GenerationStopReason.COMPLETE,
            usage(),
            cost(),
            "req",
            null,
            "{}");
    MessagePayload payload =
        MAPPER.assistantPayload(
            response,
            List.of(
                binding(),
                new ToolBinding(
                    definition("grep"),
                    new ContributorBinding("core", "grep", List.of()),
                    false,
                    null)));
    assertEquals(4, payload.message().contents().size());
    assertEquals(
        "thinking here", ((ThinkingMessageContent) payload.message().contents().get(0)).text());
    assertEquals("final text", ((TextMessageContent) payload.message().contents().get(1)).text());
    assertEquals(
        "call-1", ((ToolCallMessageContent) payload.message().contents().get(2)).toolCallId());
    assertEquals(
        "call-2", ((ToolCallMessageContent) payload.message().contents().get(3)).toolCallId());
    assertEquals("bash", ((ToolCallMessageContent) payload.message().contents().get(2)).toolName());
    AssistantMessageMetadata metadata = payload.assistantMetadata();
    assertEquals(GenerationStopReason.COMPLETE, metadata.stopReason());
    assertEquals(usage(), metadata.usage());
    assertEquals(cost(), metadata.cost());
    assertNull(payload.toolResultMetadata());
  }

  @Test
  void assistantPayloadIncludesToolCallDiagnosticsAsJsonWarnings() {
    ProviderToolCallDiagnostic diagnostic =
        new ProviderToolCallDiagnostic(
            0, "call-bad", "bash", "{\"unclosed\":", "syntax error in arguments");
    ProviderResponse response =
        new ProviderResponse(
            "part 1",
            "",
            List.of(),
            GenerationStopReason.COMPLETE,
            usage(),
            cost(),
            "req",
            null,
            "{}",
            List.of(diagnostic));

    MessagePayload payload = MAPPER.assistantPayload(response, List.of());
    assertEquals(2, payload.message().contents().size());
    assertEquals("part 1", ((TextMessageContent) payload.message().contents().get(0)).text());
    JsonMessageContent jsonContent = (JsonMessageContent) payload.message().contents().get(1);
    ProviderToolCallDiagnostic decoded =
        new ProviderToolCallDiagnosticJsonCodec().decode(jsonContent.json());
    assertEquals(diagnostic, decoded);
  }

  /** 意图：验证 complete calls 与 diagnostics 按原始 call index 顺序交错投影，而不是把 diagnostics 全部移到末尾。 */
  @Test
  void assistantPayloadPreservesInterleavedOutcomeOrderOfCallsAndDiagnostics() {
    ProviderToolCall call1 = new ProviderToolCall("call-1", "bash", "{}");
    ProviderToolCall call3 = new ProviderToolCall("call-3", "read", "{}");
    ProviderToolCallDiagnostic diag2 =
        new ProviderToolCallDiagnostic(1, "call-2", "grep", "{", "truncated");

    ProviderResponse response =
        new ProviderResponse(
            "result",
            "",
            List.of(call1, call3),
            GenerationStopReason.LENGTH,
            usage(),
            cost(),
            "req",
            null,
            "{}",
            List.of(diag2));

    MessagePayload payload = MAPPER.assistantPayload(response, List.of(binding()));
    assertEquals(4, payload.message().contents().size());
    assertEquals("result", ((TextMessageContent) payload.message().contents().get(0)).text());
    assertEquals(
        "call-1", ((ToolCallMessageContent) payload.message().contents().get(1)).toolCallId());
    assertTrue(payload.message().contents().get(2) instanceof JsonMessageContent);
    ProviderToolCallDiagnostic decodedDiag =
        new ProviderToolCallDiagnosticJsonCodec()
            .decode(((JsonMessageContent) payload.message().contents().get(2)).json());
    assertEquals(diag2, decodedDiag);
    assertEquals(
        "call-3", ((ToolCallMessageContent) payload.message().contents().get(3)).toolCallId());
  }

  @Test
  void assistantPayloadWithOnlyThinkingOrEmptyContentFallsBackToEmptyText() {
    MessagePayload thinkingOnly =
        MAPPER.assistantPayload(
            new ProviderResponse(
                "",
                "thinking",
                List.of(),
                GenerationStopReason.COMPLETE,
                usage(),
                cost(),
                null,
                null,
                "{}"),
            List.of());
    assertEquals(1, thinkingOnly.message().contents().size());
    assertTrue(thinkingOnly.message().contents().get(0) instanceof ThinkingMessageContent);

    MessagePayload empty =
        MAPPER.assistantPayload(
            new ProviderResponse(
                "",
                "",
                List.of(),
                GenerationStopReason.COMPLETE,
                usage(),
                cost(),
                null,
                null,
                "{}"),
            List.of());
    assertEquals(1, empty.message().contents().size());
    assertEquals("", ((TextMessageContent) empty.message().contents().get(0)).text());
  }

  @Test
  void assistantPayloadFreezesRendererKeyFromTheModelToolBinding() {
    ProviderResponse response =
        new ProviderResponse(
            "",
            "",
            List.of(new ProviderToolCall("call-1", "bash", "{}")),
            GenerationStopReason.COMPLETE,
            usage(),
            cost(),
            null,
            null,
            "{}");

    MessagePayload payload = MAPPER.assistantPayload(response, List.of(binding()));
    ToolCallMessageContent call = (ToolCallMessageContent) payload.message().contents().get(0);
    assertEquals("shell-command", call.rendererKey());
    // 无匹配 binding（unknown tool 槽位）时 renderer fallback 固定为 "tool"。
    MessagePayload unbound =
        MAPPER.assistantPayload(
            response,
            List.of(
                new ToolBinding(
                    definition("grep"),
                    new ContributorBinding("core", "grep", List.of()),
                    false,
                    null)));
    ToolCallMessageContent unboundCall =
        (ToolCallMessageContent) unbound.message().contents().get(0);
    assertEquals(HistoryPayloadMapper.UNBOUND_RENDERER_KEY, unboundCall.rendererKey());
  }

  /**
   * 意图：durable ToolCall 内容必须完整携带成功响应冻结的 historyAction 与 binding 冻结的 environmentName——前者是 Tool 拥有的
   * 语义，后者是后续投影判定 native 资格的事实；未冻结 action 时保持 null，投影回退到中性描述，绝不丢弃 arguments。
   */
  @Test
  void assistantPayloadFreezesHistoryActionAndEnvironmentName() {
    ProviderResponse response =
        new ProviderResponse(
            "",
            "",
            List.of(
                new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}", "read a.txt"),
                new ProviderToolCall("call-2", "fs_read", "{\"path\":\"b.txt\"}")),
            GenerationStopReason.COMPLETE,
            usage(),
            cost(),
            null,
            null,
            "{}");
    ToolBinding environmentBinding =
        new ToolBinding(
            definition("fs_read"),
            new ContributorBinding("core", "fs.read", List.of()),
            true,
            new EnvironmentId(id(1)),
            "dev");

    MessagePayload payload = MAPPER.assistantPayload(response, List.of(environmentBinding));

    ToolCallMessageContent frozen = (ToolCallMessageContent) payload.message().contents().get(0);
    assertEquals("read a.txt", frozen.historyAction());
    assertEquals("dev", frozen.environmentName());
    assertEquals("{\"path\":\"a.txt\"}", frozen.argumentsJson());
    ToolCallMessageContent unfrozen = (ToolCallMessageContent) payload.message().contents().get(1);
    assertNull(unfrozen.historyAction());
    assertEquals("dev", unfrozen.environmentName());
    assertEquals("{\"path\":\"b.txt\"}", unfrozen.argumentsJson());
  }

  @Test
  void assistantErrorPayloadUsesKindNameAndMessage() {
    AssistantErrorPayload payload =
        MAPPER.assistantErrorPayload(
            new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "token expired"));
    assertEquals("AUTHENTICATION", payload.error().code());
    assertEquals("token expired", payload.error().message());
  }

  @Test
  void toolResultPayloadSucceededMapsTextAndJsonContents() {
    ToolInvocation invocation =
        succeededInvocation(
            new ToolResult(
                "call-1",
                List.of(new TextResultContent("plain"), new JsonResultContent("{\"a\":1}")),
                false,
                "{}"));
    MessagePayload payload = MAPPER.toolResultPayload(invocation);
    ToolResultMessageContent result =
        (ToolResultMessageContent) payload.message().contents().get(0);
    assertFalse(result.error());
    assertEquals("shell-command", result.rendererKey());
    assertEquals("{}", result.detailsJson());
    assertEquals(2, result.contents().size());
    assertEquals("plain", ((TextMessageContent) result.contents().get(0)).text());
    assertEquals("{\"a\":1}", ((JsonMessageContent) result.contents().get(1)).json());
    ToolResultMetadata metadata = payload.toolResultMetadata();
    assertEquals(ToolResultStatus.SUCCEEDED, metadata.status());
    assertEquals(id(7L), metadata.assistantEntryId());
    assertEquals(3, metadata.callIndex());
    assertEquals("call-1", metadata.toolCallId());
    assertFalse(metadata.synthetic());
    assertNull(metadata.reason());
  }

  @Test
  void toolResultPayloadSucceededDegradesResourceWithoutMaterializer() {
    ResourceRef resource =
        new ResourceRef(
            "file:///report.txt",
            "text/plain",
            "report.txt",
            3L,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    ToolInvocation invocation =
        succeededInvocation(
            new ToolResult(
                "call-1",
                List.of(new ResourceResultContent(resource, "complete preview")),
                false,
                "{}"));
    // 测试意图：无物化端口时仍必须完成 history 写入，但只能保留稳定 metadata 与 preview，绝不能持久化瞬时 URI。
    MessagePayload payload = MAPPER.toolResultPayload(invocation);
    ToolResultMessageContent result =
        (ToolResultMessageContent) payload.message().contents().getFirst();
    String text = ((TextMessageContent) result.contents().getFirst()).text();
    assertEquals(
        "[Resource result available as metadata only: report.txt (text/plain)]\ncomplete preview",
        text);
    assertFalse(text.contains("file:///report.txt"));
  }

  @Test
  void toolResultPayloadSucceededUsesMaterializedContents() {
    ToolInvocation invocation =
        succeededInvocation(
            new ToolResult("call-1", List.of(new TextResultContent("")), false, "{}"));
    MessagePayload payload =
        MAPPER.toolResultPayload(
            invocation,
            List.of(
                ResourceMessageContent.media(new UUID(0L, 1L), "report.txt", "complete preview")));
    ToolResultMessageContent result =
        (ToolResultMessageContent) payload.message().contents().get(0);
    ResourceMessageContent mapped = (ResourceMessageContent) result.contents().get(0);
    assertEquals(new UUID(0L, 1L), mapped.blobId());
    assertEquals("report.txt", mapped.name());
    assertEquals("complete preview", mapped.preview());
  }

  @Test
  void toolResultPayloadSucceededWithUnmappableContentFallsBackToEmptyText() {
    ToolInvocation invocation =
        succeededInvocation(
            new ToolResult("call-1", List.of(new TextResultContent("")), false, "{}"));
    MessagePayload payload = MAPPER.toolResultPayload(invocation);
    ToolResultMessageContent result =
        (ToolResultMessageContent) payload.message().contents().get(0);
    assertEquals(1, result.contents().size());
    assertEquals("", ((TextMessageContent) result.contents().get(0)).text());
  }

  @Test
  void toolResultPayloadNonSuccessUsesErrorMessageAndCodecDetails() {
    for (ToolInvocationStatus status :
        List.of(
            ToolInvocationStatus.FAILED,
            ToolInvocationStatus.CANCELLED,
            ToolInvocationStatus.UNKNOWN)) {
      ToolInvocation invocation =
          terminalInvocation(status, new ToolInvocationError("KIND_X", "boom"));
      MessagePayload payload = MAPPER.toolResultPayload(invocation);
      ToolResultMessageContent result =
          (ToolResultMessageContent) payload.message().contents().get(0);
      assertTrue(result.error());
      assertEquals("boom", ((TextMessageContent) result.contents().get(0)).text());
      ToolInvocationError decoded = new ToolInvocationErrorJsonCodec().decode(result.detailsJson());
      assertEquals("KIND_X", decoded.kind());
      assertEquals("boom", decoded.message());
      assertEquals(
          switch (status) {
            case FAILED -> ToolResultStatus.FAILED;
            case CANCELLED -> ToolResultStatus.CANCELLED;
            case UNKNOWN -> ToolResultStatus.UNKNOWN;
            default -> throw new IllegalStateException();
          },
          payload.toolResultMetadata().status());
    }
  }

  @Test
  void toolResultPayloadSucceededWithEmptyContentsFallsBackToEmptyText() {
    ToolInvocation invocation =
        succeededInvocation(new ToolResult("call-1", List.of(), false, "{}"));
    MessagePayload payload = MAPPER.toolResultPayload(invocation);
    ToolResultMessageContent result =
        (ToolResultMessageContent) payload.message().contents().get(0);
    assertEquals(1, result.contents().size());
    assertEquals("", ((TextMessageContent) result.contents().get(0)).text());
  }

  @Test
  void toolResultPayloadSucceededPreservesResultErrorFlag() {
    ToolInvocation invocation =
        succeededInvocation(
            new ToolResult("call-1", List.of(new TextResultContent("boom")), true, "{}"));
    MessagePayload payload = MAPPER.toolResultPayload(invocation);
    ToolResultMessageContent result =
        (ToolResultMessageContent) payload.message().contents().get(0);
    assertTrue(result.error());
    assertEquals("{}", result.detailsJson());
  }

  @Test
  void toolResultPayloadRejectsUnmappableBinaryContent() {
    ToolInvocation invocation =
        succeededInvocation(
            new ToolResult(
                "call-1",
                List.of(new BinaryResultContent("application/octet-stream", new byte[] {1, 2})),
                false,
                "{}"));
    assertThrows(IllegalArgumentException.class, () -> MAPPER.toolResultPayload(invocation));
  }

  @Test
  void toolResultPayloadRejectsNonTerminalInvocation() {
    ToolInvocation ready =
        new ToolInvocation(
            id(1L),
            id(1L),
            id(7L),
            0,
            call(),
            binding(),
            ToolInvocationStatus.READY,
            0,
            null,
            null,
            null,
            NOW,
            NOW);
    assertThrows(IllegalArgumentException.class, () -> MAPPER.toolResultPayload(ready));
  }

  @Test
  void syntheticHistoryCutToolResultIsStableUnknownWithNoResultProvided() {
    MessagePayload payload =
        MAPPER.syntheticHistoryCutToolResult(
            id(7L), 2, new ToolCallMessageContent("call-9", "bash", "shell-command", "{}"));
    ToolResultMetadata metadata = payload.toolResultMetadata();
    assertEquals(id(7L), metadata.assistantEntryId());
    assertEquals("call-9", metadata.toolCallId());
    assertEquals(2, metadata.callIndex());
    assertEquals(ToolResultStatus.UNKNOWN, metadata.status());
    assertTrue(metadata.synthetic());
    assertEquals(ToolResultReason.HISTORY_CUT, metadata.reason());
    ToolResultMessageContent result =
        (ToolResultMessageContent) payload.message().contents().get(0);
    assertTrue(result.error());
    assertEquals("No result provided", ((TextMessageContent) result.contents().get(0)).text());
    assertEquals("{}", result.detailsJson());
  }

  /** unknown tool 的 immediate FAILED 槽位 binding 为空：durable renderer fallback 固定为 {@code tool}。 */
  @Test
  void failedToolResultWithoutBindingFallsBackToToolRendererKey() {
    ToolInvocation invocation =
        new ToolInvocation(
            id(1L),
            id(1L),
            id(7L),
            3,
            new ToolCall("call-1", "undeclared", "{}"),
            null,
            ToolInvocationStatus.FAILED,
            0,
            ToolApproval.notRequired(),
            null,
            new ToolInvocationError("UNKNOWN_TOOL", "unknown tool: undeclared"),
            NOW,
            NOW);
    MessagePayload payload = MAPPER.toolResultPayload(invocation);
    ToolResultMessageContent result =
        (ToolResultMessageContent) payload.message().contents().get(0);
    assertEquals("tool", result.rendererKey());
    assertTrue(result.error());
    assertEquals(
        "unknown tool: undeclared", ((TextMessageContent) result.contents().get(0)).text());
  }

  private static ToolInvocation succeededInvocation(ToolResult result) {
    return new ToolInvocation(
        id(1L),
        id(1L),
        id(7L),
        3,
        call(),
        binding(),
        ToolInvocationStatus.SUCCEEDED,
        1,
        ToolApproval.notRequired(),
        result,
        null,
        NOW,
        NOW);
  }

  private static ToolInvocation terminalInvocation(
      ToolInvocationStatus status, ToolInvocationError error) {
    return new ToolInvocation(
        id(1L),
        id(1L),
        id(7L),
        3,
        call(),
        binding(),
        status,
        status == ToolInvocationStatus.UNKNOWN ? 1 : 0,
        ToolApproval.notRequired(),
        null,
        error,
        NOW,
        NOW);
  }

  private static ToolCall call() {
    return new ToolCall("call-1", "bash", "{}");
  }

  private static ToolBinding binding() {
    return new ToolBinding(
        definition("bash"), new ContributorBinding("core", "bash", List.of()), false, null);
  }

  private static AgentToolDefinition definition(String name) {
    return new AgentToolDefinition(descriptor(name), ToolVisibility.SELECTABLE);
  }

  private static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor(
        name,
        "desc",
        name.equals("bash") ? "shell-command" : name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L);
  }

  private static ModelCost cost() {
    return new ModelCost(
        "USD",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }
}

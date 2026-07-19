package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

class SessionEntryJsonCodecTest {
  private final SessionEntryJsonCodec codec = new SessionEntryJsonCodec();

  /** 九种持久化 payload 必须逐一无损往返，包括可空的 systemPrompt。 */
  @Test
  void shouldRoundTripEveryPayloadType() {
    AgentMessage userMessage =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello")));
    AgentSnapshot snapshot =
        new AgentSnapshot(
            null,
            "model-1",
            "balanced",
            List.of("read", "write"),
            List.of("java"),
            List.of("explorer"),
            "{\"sandbox\":true}");
    List<SessionEntryPayload> payloads =
        List.of(
            new MessageEntryPayload(userMessage),
            new AgentSnapshotEntryPayload(1L, snapshot),
            new ModelChangeEntryPayload("model-2", "fast"),
            new ToolsetChangeEntryPayload(List.of("read")),
            new CompactionEntryPayload("summary", 42L, 0, "{\"reason\":\"budget\"}"),
            new BranchSummaryEntryPayload("branch summary"),
            new CustomEntryPayload("trace", "{\"enabled\":true}"),
            new CustomMessageEntryPayload(userMessage),
            new LabelEntryPayload("checkpoint"));

    assertEquals(SessionEntryType.values().length, payloads.size());
    assertEquals(
        Set.copyOf(Arrays.asList(SessionEntryType.values())),
        payloads.stream().map(SessionEntryPayload::type).collect(Collectors.toSet()));
    for (SessionEntryPayload payload : payloads) {
      assertEquals(
          payload, codec.decode(payload.type(), codec.encode(payload)), payload.type().name());
    }
  }

  /** 八种消息 content 必须无损往返，ToolCall 与 ToolResult 的调用身份必须一致。 */
  @Test
  void shouldRoundTripEveryMessageContent() {
    AgentMessage user =
        new AgentMessage(
            AgentMessageRole.USER,
            List.of(
                new TextMessageContent("text"),
                new ImageMessageContent("image/png", "data:image/png;base64,AA=="),
                new AudioMessageContent("audio/wav", "https://example.test/audio.wav"),
                new ThinkingMessageContent("reasoning"),
                new JsonMessageContent("[1,{\"ok\":true}]"),
                new ArtifactMessageContent("artifact-1", "text/plain", null)));
    AgentMessage assistant =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new TextMessageContent("calling"),
                new ToolCallMessageContent("call-1", "read", "{\"path\":\"README.md\"}")));
    AgentMessage tool =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-1",
                    "read",
                    List.of(
                        new TextMessageContent("done"),
                        new ArtifactMessageContent(
                            "artifact-2", "application/json", "{\"lines\":12}")),
                    true,
                    "{\"exitCode\":1}")));

    AgentMessage decodedUser = roundTrip(user);
    AgentMessage decodedAssistant = roundTrip(assistant);
    AgentMessage decodedTool = roundTrip(tool);

    assertEquals(user, decodedUser);
    assertEquals(assistant, decodedAssistant);
    assertEquals(tool, decodedTool);
    ToolCallMessageContent call =
        assertInstanceOf(ToolCallMessageContent.class, decodedAssistant.contents().get(1));
    ToolResultMessageContent result =
        assertInstanceOf(ToolResultMessageContent.class, decodedTool.contents().get(0));
    assertEquals(call.toolCallId(), result.toolCallId());
    assertEquals(call.toolName(), result.toolName());
    assertNull(
        assertInstanceOf(ArtifactMessageContent.class, decodedUser.contents().get(5)).preview());
  }

  /** Assistant stop reason、usage 与 cost 必须完整持久化，不能在 Session 历史中丢失。 */
  @Test
  void shouldRoundTripAssistantMetadata() {
    MessageEntryPayload payload =
        new MessageEntryPayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("answer"))),
            assistantMetadata());

    MessageEntryPayload decoded =
        assertInstanceOf(
            MessageEntryPayload.class,
            codec.decode(SessionEntryType.MESSAGE, codec.encode(payload)));

    assertEquals(payload, decoded);
    assertEquals(321L, decoded.assistantMetadata().usage().inputTokens());
    assertEquals(new BigDecimal("0.000004200000"), decoded.assistantMetadata().cost().total());
    assertEquals(new BigDecimal("0.000004200000"), decoded.assistantMetadata().cost().amount());
  }

  /** Payload 根节点必须是完整且字段精确的 JSON object。 */
  @Test
  void shouldRejectMalformedPayloadEnvelopes() {
    assertMalformed(SessionEntryType.LABEL, "not-json");
    assertMalformed(SessionEntryType.LABEL, "[]");
    assertMalformed(SessionEntryType.LABEL, "{}");
    assertMalformed(SessionEntryType.LABEL, "{\"label\":\"x\",\"extra\":1}");
    assertMalformed(SessionEntryType.MODEL_CHANGE, "{\"modelId\":\"m\"}");
    assertMalformed(SessionEntryType.MESSAGE, "{\"message\":[],\"assistantMetadata\":null}");
  }

  /** Message 边界拒绝未知 role/type、错误 object/array 形状和空白语义值。 */
  @Test
  void shouldRejectMalformedMessagesAndContents() {
    assertMalformed(
        SessionEntryType.MESSAGE,
        nonAssistantMessage(
            "{\"role\":\"UNKNOWN\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]}"));
    assertMalformed(
        SessionEntryType.MESSAGE,
        nonAssistantMessage("{\"role\":1,\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]}"));
    assertMalformed(
        SessionEntryType.MESSAGE, nonAssistantMessage("{\"role\":\"USER\",\"contents\":{}}"));
    assertMalformed(
        SessionEntryType.MESSAGE, nonAssistantMessage("{\"role\":\"USER\",\"contents\":[1]}"));
    assertMalformed(
        SessionEntryType.MESSAGE,
        nonAssistantMessage("{\"role\":\"USER\",\"contents\":[{\"text\":\"x\"}]}"));
    assertMalformed(
        SessionEntryType.MESSAGE,
        nonAssistantMessage("{\"role\":\"USER\",\"contents\":[{\"type\":\"unknown\"}]}"));
    assertMalformed(
        SessionEntryType.MESSAGE,
        nonAssistantMessage(
            "{\"role\":\"USER\",\"contents\":[{\"type\":\"image\",\"mediaType\":\""
                + " \",\"source\":\"uri\"}]}"));
    assertMalformed(
        SessionEntryType.MESSAGE,
        nonAssistantMessage(
            "{\"role\":\"USER\",\"contents\":[{\"type\":\"json\",\"json\":\"not-json\"}]}"));
  }

  /** Snapshot/list/JSON object 字段必须保持声明的 JSON 类型。 */
  @Test
  void shouldRejectMalformedSnapshotAndJsonFields() {
    String snapshotPrefix =
        "{\"snapshot\":{\"systemPrompt\":null,\"modelId\":\"m\",\"variant\":\"v\",";
    String snapshotSuffix =
        "\"skills\":[],\"allowedSubagents\":[],\"executionPolicyJson\":\"{}\"}}";
    assertMalformed(
        SessionEntryType.AGENT_SNAPSHOT, snapshotPrefix + "\"tools\":{}," + snapshotSuffix);
    assertMalformed(
        SessionEntryType.AGENT_SNAPSHOT, snapshotPrefix + "\"tools\":[1]," + snapshotSuffix);
    assertMalformed(
        SessionEntryType.AGENT_SNAPSHOT,
        "{\"snapshot\":{\"systemPrompt\":1,\"modelId\":\"m\",\"variant\":\"v\","
            + "\"tools\":[],\"skills\":[],\"allowedSubagents\":[],\"executionPolicyJson\":\"{}\"}}");
    assertMalformed(
        SessionEntryType.AGENT_SNAPSHOT,
        "{\"snapshot\":{\"systemPrompt\":null,\"modelId\":\"m\",\"variant\":\"v\","
            + "\"tools\":[],\"skills\":[],\"allowedSubagents\":[],\"executionPolicyJson\":\"[]\"}}");
    assertMalformed(SessionEntryType.CUSTOM, "{\"name\":\"x\",\"dataJson\":\"[]\"}");
    assertMalformed(SessionEntryType.MODEL_CHANGE, "{\"modelId\":1,\"variant\":\"fast\"}");
  }

  /** 数字、布尔值和 nullable 字段必须使用严格类型及合法范围。 */
  @Test
  void shouldRejectMalformedNumbersBooleansAndNullableFields() {
    assertMalformed(
        SessionEntryType.COMPACTION,
        "{\"summary\":\"x\",\"firstKeptEntryId\":0,\"tokensBefore\":1,\"detailsJson\":\"{}\"}");
    assertMalformed(
        SessionEntryType.COMPACTION,
        "{\"summary\":\"x\",\"firstKeptEntryId\":1.5,\"tokensBefore\":1,\"detailsJson\":\"{}\"}");
    assertMalformed(
        SessionEntryType.COMPACTION,
        "{\"summary\":\"x\",\"firstKeptEntryId\":1,\"tokensBefore\":-1,\"detailsJson\":\"{}\"}");
    assertMalformed(
        SessionEntryType.COMPACTION,
        "{\"summary\":\"x\",\"firstKeptEntryId\":1,\"tokensBefore\":1.5,\"detailsJson\":\"{}\"}");
    assertMalformed(
        SessionEntryType.MESSAGE,
        toolMessage(
            "{\"type\":\"tool_result\",\"toolCallId\":\"c\",\"toolName\":\"t\","
                + "\"contents\":[{\"type\":\"text\",\"text\":\"x\"}],\"error\":\"false\","
                + "\"detailsJson\":\"{}\"}"));
    assertMalformed(
        SessionEntryType.MESSAGE,
        nonAssistantMessage(
            "{\"role\":\"USER\",\"contents\":[{\"type\":\"artifact\","
                + "\"artifactId\":\"a\",\"mediaType\":\"text/plain\",\"preview\":1}]}"));
  }

  /** Assistant metadata 与角色必须严格一致，坏 stop reason、usage、cost 均不得进入历史。 */
  @Test
  void shouldRejectInconsistentOrMalformedAssistantMetadata() {
    AgentMessage assistant =
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("answer")));
    AgentMessage user =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("question")));
    assertThrows(IllegalArgumentException.class, () -> new MessageEntryPayload(assistant));
    assertThrows(
        IllegalArgumentException.class, () -> new MessageEntryPayload(user, assistantMetadata()));
    AgentMessage toolCalling =
        new AgentMessage(
            AgentMessageRole.ASSISTANT, List.of(new ToolCallMessageContent("call", "read", "{}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessageEntryPayload(toolCalling, assistantMetadata()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessageEntryPayload(assistant, assistantMetadata(ProviderStopReason.TOOL_CALLS)));

    assertMalformed(SessionEntryType.MESSAGE, messageWithMetadata("ASSISTANT", "null"));
    assertMalformed(SessionEntryType.MESSAGE, messageWithMetadata("USER", validMetadataJson()));
    assertMalformed(
        SessionEntryType.MESSAGE,
        messageWithMetadata("ASSISTANT", validMetadataJson().replace("COMPLETED", "UNKNOWN")));
    assertMalformed(
        SessionEntryType.MESSAGE,
        messageWithMetadata(
            "ASSISTANT", validMetadataJson().replace("\"inputTokens\":321", "\"inputTokens\":-1")));
    assertMalformed(
        SessionEntryType.MESSAGE,
        messageWithMetadata(
            "ASSISTANT",
            validMetadataJson().replace("\"total\":\"0.000004200000\"", "\"total\":\"bad\"")));
    assertMalformed(
        SessionEntryType.MESSAGE,
        messageWithMetadata(
            "ASSISTANT",
            validMetadataJson().replace("\"total\":\"0.000004200000\"", "\"total\":\"-1\"")));
    assertMalformed(
        SessionEntryType.MESSAGE,
        messageWithMetadata(
            "ASSISTANT",
            validMetadataJson().replace("\"currency\":\"USD\"", "\"currency\":\" \"")));
    assertMalformed(
        SessionEntryType.MESSAGE,
        messageWithMetadata(
            "ASSISTANT",
            validMetadataJson().replace("\"inputTokens\":321", "\"inputTokens\":1.5")));
    assertMalformed(
        SessionEntryType.MESSAGE,
        messageWithMetadata(
            "ASSISTANT", validMetadataJson().replace("\"cost\":", "\"extra\":true,\"cost\":")));
    assertMalformed(
        SessionEntryType.MESSAGE,
        messageWithMetadata(
            "ASSISTANT",
            validMetadataJson()
                .replace("\"cacheWriteLongTokens\":0", "\"cacheWriteLongTokens\":-1")));
    assertMalformed(
        SessionEntryType.MESSAGE,
        messageWithMetadata(
            "ASSISTANT",
            validMetadataJson()
                .replace(
                    "\"cacheWrite\":\"0.000001200000\"", "\"cacheWrite\":\"-0.000001200000\"")));
    assertMalformed(
        SessionEntryType.MESSAGE,
        messageWithMetadata(
            "ASSISTANT",
            validMetadataJson().replace("\"total\":\"0.000004200000\"", "\"total\":\"1\"")));
  }

  /** ToolResult 内容不能递归嵌套 ToolCall 或 ToolResult。 */
  @Test
  void shouldRejectNestedToolContents() {
    String nestedCall =
        "{\"type\":\"tool_call\",\"toolCallId\":\"nested\",\"toolName\":\"read\",\"argumentsJson\":\"{}\"}";
    String nestedResult =
        "{\"type\":\"tool_result\",\"toolCallId\":\"nested\",\"toolName\":\"read\","
            + "\"contents\":[{\"type\":\"text\",\"text\":\"x\"}],\"error\":false,\"detailsJson\":\"{}\"}";

    assertMalformed(SessionEntryType.MESSAGE, toolMessage(toolResultWithContents(nestedCall)));
    assertMalformed(SessionEntryType.MESSAGE, toolMessage(toolResultWithContents(nestedResult)));
    assertMalformed(
        SessionEntryType.MESSAGE,
        toolMessage(
            "{\"type\":\"tool_result\",\"toolCallId\":\"c\",\"toolName\":\" \","
                + "\"contents\":[{\"type\":\"text\",\"text\":\"x\"}],\"error\":false,"
                + "\"detailsJson\":\"{}\"}"));
  }

  private AgentMessage roundTrip(AgentMessage message) {
    MessageEntryPayload payload =
        message.role() == AgentMessageRole.ASSISTANT
            ? new MessageEntryPayload(
                message,
                assistantMetadata(
                    message.contents().stream().anyMatch(ToolCallMessageContent.class::isInstance)
                        ? ProviderStopReason.TOOL_CALLS
                        : ProviderStopReason.COMPLETED))
            : new MessageEntryPayload(message);
    MessageEntryPayload decoded =
        assertInstanceOf(
            MessageEntryPayload.class,
            codec.decode(SessionEntryType.MESSAGE, codec.encode(payload)));
    return decoded.message();
  }

  private void assertMalformed(SessionEntryType type, String json) {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(type, json));
    assertEquals("malformed " + type.value() + " payload", exception.getMessage());
  }

  private static AssistantMessageMetadata assistantMetadata() {
    return assistantMetadata(ProviderStopReason.COMPLETED);
  }

  private static AssistantMessageMetadata assistantMetadata(ProviderStopReason stopReason) {
    return new AssistantMessageMetadata(
        stopReason,
        new ModelUsage(321, 45, 6, 7, 0, 8, 387),
        new ModelCost(
            "USD",
            new BigDecimal("0.000001000000"),
            new BigDecimal("0.000001000000"),
            new BigDecimal("0.000001000000"),
            new BigDecimal("0.000001200000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("0.000004200000")));
  }

  private static String validMetadataJson() {
    return "{\"stopReason\":\"COMPLETED\",\"usage\":{\"inputTokens\":321,"
        + "\"outputTokens\":45,\"cacheReadTokens\":6,\"cacheWriteTokens\":7,"
        + "\"cacheWriteLongTokens\":0,\"reasoningTokens\":8,\"providerTotalTokens\":387},"
        + "\"cost\":{\"currency\":\"USD\",\"input\":\"0.000001000000\","
        + "\"output\":\"0.000001000000\",\"cacheRead\":\"0.000001000000\","
        + "\"cacheWrite\":\"0.000001200000\",\"cacheWriteLong\":\"0\","
        + "\"reasoning\":\"0\",\"total\":\"0.000004200000\"}}";
  }

  private static String messageWithMetadata(String role, String metadataJson) {
    return "{\"message\":{\"role\":\""
        + role
        + "\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]},"
        + "\"assistantMetadata\":"
        + metadataJson
        + "}";
  }

  private static String nonAssistantMessage(String messageJson) {
    return "{\"message\":" + messageJson + ",\"assistantMetadata\":null}";
  }

  private static String toolMessage(String content) {
    return nonAssistantMessage("{\"role\":\"TOOL\",\"contents\":[" + content + "]}");
  }

  private static String toolResultWithContents(String content) {
    return "{\"type\":\"tool_result\",\"toolCallId\":\"c\",\"toolName\":\"read\","
        + "\"contents\":["
        + content
        + "],\"error\":false,\"detailsJson\":\"{}\"}";
  }
}

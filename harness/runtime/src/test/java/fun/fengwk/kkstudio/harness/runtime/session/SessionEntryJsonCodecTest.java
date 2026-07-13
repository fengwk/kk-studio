package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

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
            new AgentSnapshotEntryPayload(snapshot),
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

  /** Payload 根节点必须是完整且字段精确的 JSON object。 */
  @Test
  void shouldRejectMalformedPayloadEnvelopes() {
    assertMalformed(SessionEntryType.LABEL, "not-json");
    assertMalformed(SessionEntryType.LABEL, "[]");
    assertMalformed(SessionEntryType.LABEL, "{}");
    assertMalformed(SessionEntryType.LABEL, "{\"label\":\"x\",\"extra\":1}");
    assertMalformed(SessionEntryType.MODEL_CHANGE, "{\"modelId\":\"m\"}");
    assertMalformed(SessionEntryType.MESSAGE, "{\"message\":[]}");
  }

  /** Message 边界拒绝未知 role/type、错误 object/array 形状和空白语义值。 */
  @Test
  void shouldRejectMalformedMessagesAndContents() {
    assertMalformed(
        SessionEntryType.MESSAGE,
        "{\"message\":{\"role\":\"UNKNOWN\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]}}");
    assertMalformed(
        SessionEntryType.MESSAGE,
        "{\"message\":{\"role\":1,\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]}}");
    assertMalformed(SessionEntryType.MESSAGE, "{\"message\":{\"role\":\"USER\",\"contents\":{}}}");
    assertMalformed(SessionEntryType.MESSAGE, "{\"message\":{\"role\":\"USER\",\"contents\":[1]}}");
    assertMalformed(
        SessionEntryType.MESSAGE,
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"text\":\"x\"}]}}");
    assertMalformed(
        SessionEntryType.MESSAGE,
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"unknown\"}]}}");
    assertMalformed(
        SessionEntryType.MESSAGE,
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"image\",\"mediaType\":\""
            + " \",\"source\":\"uri\"}]}}");
    assertMalformed(
        SessionEntryType.MESSAGE,
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"json\",\"json\":\"not-json\"}]}}");
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
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"artifact\","
            + "\"artifactId\":\"a\",\"mediaType\":\"text/plain\",\"preview\":1}]}}");
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
    MessageEntryPayload decoded =
        assertInstanceOf(
            MessageEntryPayload.class,
            codec.decode(SessionEntryType.MESSAGE, codec.encode(new MessageEntryPayload(message))));
    return decoded.message();
  }

  private void assertMalformed(SessionEntryType type, String json) {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(type, json));
    assertEquals("malformed " + type.value() + " payload", exception.getMessage());
  }

  private static String toolMessage(String content) {
    return "{\"message\":{\"role\":\"TOOL\",\"contents\":[" + content + "]}}";
  }

  private static String toolResultWithContents(String content) {
    return "{\"type\":\"tool_result\",\"toolCallId\":\"c\",\"toolName\":\"read\","
        + "\"contents\":["
        + content
        + "],\"error\":false,\"detailsJson\":\"{}\"}";
  }
}

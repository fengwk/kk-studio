package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ResourceRef;

import java.util.List;

/** Shared strict AgentMessage/content codec: canonical order, round trip and boundary rejection. */
class AgentMessageJsonCodecTest {

  private final AgentMessageJsonCodec codec = new AgentMessageJsonCodec();

  @Test
  void roundTripsAllContentTypes() {
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new TextMessageContent("answer"),
                new ImageMessageContent("image/png", "data:image/png;base64,AA=="),
                new AudioMessageContent("audio/wav", "https://example.test/audio.wav"),
                new ThinkingMessageContent("reasoning"),
                new JsonMessageContent("[1,{\"ok\":true}]"),
                new ToolCallMessageContent("call-1", "read", "{\"path\":\"README.md\"}"),
                new ResourceMessageContent(
                    new ResourceRef(
                        "data:text/plain,hello",
                        "text/plain",
                        "hello.txt",
                        5L,
                        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),
                    "preview")));

    assertEquals(message, codec.decode(codec.encode(message)));
    assertEquals(message, codec.decodeNode(codec.encodeNode(message)));
  }

  @Test
  void roundTripsToolMessageAndNestedContents() {
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-1",
                    "read",
                    List.of(new TextMessageContent("ok"), new JsonMessageContent("{}")),
                    false,
                    "{\"exitCode\":0}")));

    assertEquals(message, codec.decode(codec.encode(message)));
  }

  @Test
  void encodesCanonicalFieldOrder() {
    assertEquals(
        "{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hello\"}]}",
        codec.encode(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello")))));
    assertEquals(
        "{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"tool_call\",\"toolCallId\":\"call-1\","
            + "\"toolName\":\"read\",\"argumentsJson\":\"{\\\"path\\\":\\\"README.md\\\"}\"}]}",
        codec.encode(
            new AgentMessage(
                AgentMessageRole.ASSISTANT,
                List.of(
                    new ToolCallMessageContent("call-1", "read", "{\"path\":\"README.md\"}")))));
    assertEquals(
        "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"call-1\","
            + "\"toolName\":\"read\",\"contents\":[{\"type\":\"text\",\"text\":\"ok\"}],"
            + "\"error\":false,\"detailsJson\":\"{}\"}]}",
        codec.encode(
            new AgentMessage(
                AgentMessageRole.TOOL,
                List.of(
                    new ToolResultMessageContent(
                        "call-1", "read", List.of(new TextMessageContent("ok")), false, "{}")))));
    // resource 是扁平精确字段：所有可空字段显式写出为 JSON null。
    assertEquals(
        "{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"resource\","
            + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
            + "\"name\":\"a.txt\",\"size\":3,"
            + "\"sha256\":\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\","
            + "\"preview\":null}]}",
        codec.encode(
            new AgentMessage(
                AgentMessageRole.ASSISTANT,
                List.of(
                    new ResourceMessageContent(
                        new ResourceRef(
                            "https://example.com/a",
                            "text/plain",
                            "a.txt",
                            3L,
                            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                        null)))));
  }

  @Test
  void roundTripsResourceWithPreviewAndAllowsEmptyTextAndThinking() {
    AgentMessage resource =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new ResourceMessageContent(
                    new ResourceRef(
                        "data:text/plain,hello",
                        "text/plain",
                        null,
                        5L,
                        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),
                    "preview")));
    AgentMessage emptyText =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(new TextMessageContent(""), new ThinkingMessageContent("")));

    assertEquals(resource, codec.decode(codec.encode(resource)));
    assertEquals(emptyText, codec.decode(codec.encode(emptyText)));
  }

  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]} {}"));
  }

  @Test
  void rejectsMalformedNullOrWrongRootJson() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeNode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
  }

  @Test
  void rejectsUnknownRoleAndContentType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"role\":\"FOO\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode("{\"role\":\"USER\",\"contents\":[{\"type\":\"video\",\"url\":\"x\"}]}"));
  }

  @Test
  void rejectsMissingOrWrongTypedMessageFields() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"role\":\"USER\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"role\":1,\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode("{\"role\":\"USER\",\"contents\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"role\":\"USER\",\"contents\":[{\"text\":\"x\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"role\":\"USER\",\"contents\":[{\"type\":5,\"text\":\"x\"}]}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode("{\"role\":\"USER\",\"contents\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"x\",\"extra\":1}]}"));
  }

  @Test
  void rejectsWrongTypedOrBlankContentFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"role\":\"USER\",\"contents\":[{\"type\":\"text\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":5}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode("{\"role\":\"USER\",\"contents\":[{\"type\":\"thinking\",\"text\":5}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"image\",\"mediaType\":\" \",\"source\":\"x\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"audio\",\"mediaType\":\"a\",\"source\":5}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":null,\"sha256\":null,\"preview\":5}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"\","
                    + "\"name\":null,\"size\":null,\"sha256\":null,\"preview\":null}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":null,\"sha256\":null}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":5,\"size\":null,\"sha256\":null,\"preview\":null}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":true,\"sha256\":null,\"preview\":null}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":null,\"sha256\":null,\"preview\":5}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":null,\"sha256\":null,\"preview\":null,\"extra\":1}]}"));
  }

  @Test
  void rejectsInvalidRawJsonOnDecode() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"json\",\"json\":\"{bad\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"tool_call\",\"toolCallId\":\"c\",\"toolName\":\"t\",\"argumentsJson\":\"[1]\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c\",\"toolName\":\"t\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}],\"error\":false,\"detailsJson\":\"\\\"x\\\"\"}]}"));
  }

  @Test
  void rejectsInvalidRawJsonOnEncode() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.encode(
                new AgentMessage(AgentMessageRole.USER, List.of(new JsonMessageContent("{bad")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.encode(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new ToolCallMessageContent("c", "t", "[1]")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.encode(
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "c", "t", List.of(new TextMessageContent("x")), false, "5")))));
  }

  @Test
  void rejectsNestedToolCallOrToolResultInToolResultContents() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.encode(
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "c",
                            "t",
                            List.of(new ToolCallMessageContent("c2", "t2", "{}")),
                            false,
                            "{}")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.encode(
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "c",
                            "t",
                            List.of(
                                new ToolResultMessageContent(
                                    "c2", "t2", List.of(new TextMessageContent("x")), false, "{}")),
                            false,
                            "{}")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c\","
                    + "\"toolName\":\"t\",\"contents\":[{\"type\":\"tool_call\",\"toolCallId\":\"c2\","
                    + "\"toolName\":\"t2\",\"argumentsJson\":\"{}\"}],\"error\":false,\"detailsJson\":\"{}\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c\","
                    + "\"toolName\":\"t\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c2\","
                    + "\"toolName\":\"t2\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}],"
                    + "\"error\":false,\"detailsJson\":\"{}\"}],\"error\":false,\"detailsJson\":\"{}\"}]}"));
  }

  @Test
  void rejectsRoleContentMismatchesAndWrongTypedToolResultFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"tool_call\",\"toolCallId\":\"c\",\"toolName\":\"t\",\"argumentsJson\":\"{}\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c\",\"toolName\":\"t\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}],\"error\":\"no\",\"detailsJson\":\"{}\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c\",\"toolName\":\"t\",\"contents\":{},\"error\":false,\"detailsJson\":\"{}\"}]}"));
  }
}

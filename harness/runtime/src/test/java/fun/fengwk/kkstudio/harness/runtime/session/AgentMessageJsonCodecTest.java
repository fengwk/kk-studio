package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;

import java.util.List;
import java.util.UUID;

/** Shared 严格 AgentMessage/content codec：canonical 顺序、round-trip 与 boundary 拒绝。 */
class AgentMessageJsonCodecTest {

  private final AgentMessageJsonCodec codec = new AgentMessageJsonCodec();

  private static final UUID TIER_BLOB_ID = UUID.fromString("0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01");

  @Test
  void roundTripsAllContentTypes() {
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new TextMessageContent("answer"),
                new ThinkingMessageContent("reasoning"),
                new JsonMessageContent("[1,{\"ok\":true}]"),
                new ToolCallMessageContent("call-1", "read", "read", "{\"path\":\"README.md\"}"),
                ResourceMessageContent.media(
                    UUID.fromString("0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01"),
                    "hello.txt",
                    "preview"),
                ResourceMessageContent.externalizedText(
                    UUID.fromString("0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01"),
                    "tool-result.txt",
                    100L,
                    5L,
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
                    "read",
                    List.of(
                        new TextMessageContent("ok"),
                        ResourceMessageContent.media(
                            UUID.fromString("0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01"), "result.txt"),
                        new JsonMessageContent("{}")),
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
            + "\"toolName\":\"read\",\"rendererKey\":\"read\","
            + "\"argumentsJson\":\"{\\\"path\\\":\\\"README.md\\\"}\","
            + "\"historyAction\":null,\"environmentName\":null}]}",
        codec.encode(
            new AgentMessage(
                AgentMessageRole.ASSISTANT,
                List.of(
                    new ToolCallMessageContent(
                        "call-1", "read", "read", "{\"path\":\"README.md\"}")))));
    assertEquals(
        "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"call-1\","
            + "\"toolName\":\"read\",\"rendererKey\":\"read\","
            + "\"contents\":[{\"type\":\"text\",\"text\":\"ok\"}],"
            + "\"error\":false,\"detailsJson\":\"{}\"}]}",
        codec.encode(
            new AgentMessage(
                AgentMessageRole.TOOL,
                List.of(
                    new ToolResultMessageContent(
                        "call-1",
                        "read",
                        "read",
                        List.of(new TextMessageContent("ok")),
                        false,
                        "{}")))));
    // resource 是扁平精确字段：blobId/name 必须显式写出，可空 totals/preview/imageTier 字段显式写出为 JSON null。
    assertEquals(
        "{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"resource\","
            + "\"blobId\":\"0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01\","
            + "\"name\":\"a.txt\",\"totalBytes\":null,\"totalLines\":null,\"preview\":null,"
            + "\"imageTier\":null}]}",
        codec.encode(
            new AgentMessage(
                AgentMessageRole.ASSISTANT,
                List.of(
                    ResourceMessageContent.media(
                        UUID.fromString("0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01"), "a.txt")))));
  }

  @Test
  void roundTripsFrozenToolCallHistoryFacts() {
    // 冻结的 action 与 Environment 名是 durable 事实：非空/空白边界都必须无损往返，且缺失或空白必须被拒绝。
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new ToolCallMessageContent(
                    "call-1", "fs_read", "fs.read", "{\"path\":\"a.txt\"}", "read a.txt", "dev"),
                new ToolCallMessageContent("call-2", "bash", "bash", "{}")));
    assertEquals(message, codec.decode(codec.encode(message)));
    ToolCallMessageContent decoded =
        (ToolCallMessageContent) codec.decode(codec.encode(message)).contents().get(0);
    assertEquals("read a.txt", decoded.historyAction());
    assertEquals("dev", decoded.environmentName());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"tool_call\","
                    + "\"toolCallId\":\"c\",\"toolName\":\"t\",\"rendererKey\":\"t\","
                    + "\"argumentsJson\":\"{}\",\"environmentName\":null}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"tool_call\","
                    + "\"toolCallId\":\"c\",\"toolName\":\"t\",\"rendererKey\":\"t\","
                    + "\"argumentsJson\":\"{}\",\"historyAction\":\" \",\"environmentName\":null}]}"));
  }

  @Test
  void roundTripsResourceWithPreviewAndAllowsEmptyTextAndThinking() {
    AgentMessage resource =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                ResourceMessageContent.media(
                    UUID.fromString("0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01"),
                    "hello.txt",
                    "preview")));
    AgentMessage emptyText =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(new TextMessageContent(""), new ThinkingMessageContent("")));

    assertEquals(resource, codec.decode(codec.encode(resource)));
    assertEquals(emptyText, codec.decode(codec.encode(emptyText)));
  }

  /** 意图：档位是 durable 事实，必须用 wire 名称无损往返；未知名称、枚举名与缺失字段都显式拒绝（绝不静默套用默认档位）。 */
  @Test
  void roundTripsDurableImageInputTiers() {
    for (ImageInputTier tier : ImageInputTier.values()) {
      AgentMessage message =
          new AgentMessage(
              AgentMessageRole.ASSISTANT,
              List.of(ResourceMessageContent.media(TIER_BLOB_ID, "photo.png", "preview", tier)));

      String json = codec.encode(message);

      assertTrue(
          json.contains("\"imageTier\":\"" + tier.wireName() + "\""),
          "durable JSON 必须写出档位 wire 名称：" + json);
      assertEquals(message, codec.decode(json));
      assertEquals(message, codec.decodeNode(codec.encodeNode(message)));
    }

    // 枚举名、未知名称与非文本取值都不是合法 durable 事实。
    for (String wireName : List.of("P720", "720p", "4K", "")) {
      assertThrows(
          IllegalArgumentException.class, () -> decodeResourceWithTier(wireName), wireName);
    }
    assertThrows(IllegalArgumentException.class, () -> decodeResourceWithTier("720"));
    // 缺失字段 fail closed：历史 payload 不会被静默解释成某个档位。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"resource\","
                    + "\"blobId\":\"0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01\","
                    + "\"name\":\"photo.png\",\"totalBytes\":null,\"totalLines\":null,"
                    + "\"preview\":null}]}"));
  }

  /** 意图：durable payload 只保存 blob 引用与档位，绝不携带内联媒体（无 Base64、无 data URI、无 URL）。 */
  @Test
  void durableEncodingCarriesNoInlineMedia() {
    String json =
        codec.encode(
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(
                    ResourceMessageContent.media(
                        TIER_BLOB_ID, "photo.png", "preview", ImageInputTier.P1080),
                    ResourceMessageContent.media(
                        TIER_BLOB_ID, "original.png", null, ImageInputTier.ORIGINAL))));

    assertFalse(json.contains("base64"), json);
    assertFalse(json.contains("data:"), json);
    assertFalse(json.contains("http"), json);
  }

  /** 意图：请求形态（幂等哈希输入）携带客户端冻结的档位；不同档位是不同请求，且 ATTACHMENT 永不进入 durable payload。 */
  @Test
  void requestFormCarriesFrozenAttachmentTierAndStaysTransient() {
    UUID uploadId = UUID.fromString("1a4e2f0c-1f2b-4e6d-9c3a-77f0a1b2c3d4");
    String p720 =
        codec.encodeRequestNode(attachmentMessage(uploadId, ImageInputTier.P720)).toString();
    String p1080 =
        codec.encodeRequestNode(attachmentMessage(uploadId, ImageInputTier.P1080)).toString();

    assertTrue(p720.contains("\"imageTier\":\"720P\""), p720);
    assertNotEquals(p720, p1080, "同一 upload 的不同档位必须产生不同请求形态");
    // 未选择档位：请求形态显式为 null，默认档位由命令接受边界决定。
    assertTrue(
        codec
            .encodeRequestNode(attachmentMessage(uploadId, null))
            .toString()
            .contains("\"imageTier\":null"));

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encode(attachmentMessage(uploadId, ImageInputTier.P720)));
    assertThrows(
        IllegalArgumentException.class, () -> codec.encodeNode(attachmentMessage(uploadId, null)));
  }

  private static AgentMessage attachmentMessage(UUID uploadId, ImageInputTier tier) {
    return new AgentMessage(
        AgentMessageRole.USER, List.of(new AttachmentMessageContent(uploadId, tier)));
  }

  private AgentMessage decodeResourceWithTier(String imageTierJsonValue) {
    return codec.decode(
        "{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"resource\","
            + "\"blobId\":\"0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01\","
            + "\"name\":\"photo.png\",\"totalBytes\":null,\"totalLines\":null,"
            + "\"preview\":null,\"imageTier\":"
            + imageTierJsonValue
            + "}]}");
  }

  @Test
  void rejectsTransientMediaFormsOnEncode() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.encode(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(new ImageMessageContent("image/png", "data:image/png;base64,AA==")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.encode(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(new AudioMessageContent("audio/wav", "https://example.test/a.wav")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.encode(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(new VideoMessageContent("video/mp4", "https://example.test/v.mp4")))));
  }

  @Test
  void rejectsTransientMediaFormsOnDecode() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"image\",\"mediaType\":\"image/png\","
                    + "\"source\":\"data:image/png;base64,AA==\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"audio\",\"mediaType\":\"audio/wav\","
                    + "\"source\":\"https://example.test/a.wav\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"video\",\"mediaType\":\"video/mp4\","
                    + "\"source\":\"https://example.test/v.mp4\"}]}"));
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
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"document\",\"url\":\"x\"}]}"));
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
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\",\"blobId\":\"x\","
                    + "\"name\":\"a\",\"totalBytes\":null,\"totalLines\":null,\"preview\":null}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"blobId\":\"0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01\","
                    + "\"name\":null,\"totalBytes\":null,\"totalLines\":null,\"preview\":null}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"blobId\":\"0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01\","
                    + "\"name\":\"\",\"totalBytes\":null,\"totalLines\":null,\"preview\":null}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"blobId\":\"0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01\","
                    + "\"name\":5,\"totalBytes\":null,\"totalLines\":null,\"preview\":null}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"blobId\":\"0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01\","
                    + "\"name\":\"a\",\"totalBytes\":null,\"totalLines\":null,\"preview\":5}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"blobId\":\"0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01\","
                    + "\"name\":\"a\",\"totalBytes\":null,\"totalLines\":null,\"preview\":null,\"extra\":1}]}"));
    // 遗漏或多余字段
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\","
                    + "\"blobId\":\"0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01\","
                    + "\"name\":\"a\",\"preview\":null}]}"));
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
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"tool_call\",\"toolCallId\":\"c\","
                    + "\"toolName\":\"t\",\"rendererKey\":\"t\",\"argumentsJson\":\"[1]\","
                    + "\"historyAction\":null,\"environmentName\":null}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c\","
                    + "\"toolName\":\"t\",\"rendererKey\":\"t\","
                    + "\"contents\":[{\"type\":\"text\",\"text\":\"x\"}],"
                    + "\"error\":false,\"detailsJson\":\"\\\"x\\\"\"}]}"));
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
                    List.of(new ToolCallMessageContent("c", "t", "t", "[1]")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.encode(
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "c", "t", "t", List.of(new TextMessageContent("x")), false, "5")))));
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
                            "t",
                            List.of(new ToolCallMessageContent("c2", "t2", "t2", "{}")),
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
                            "t",
                            List.of(
                                new ToolResultMessageContent(
                                    "c2",
                                    "t2",
                                    "t2",
                                    List.of(new TextMessageContent("x")),
                                    false,
                                    "{}")),
                            false,
                            "{}")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c\","
                    + "\"toolName\":\"t\",\"rendererKey\":\"t\","
                    + "\"contents\":[{\"type\":\"tool_call\",\"toolCallId\":\"c2\","
                    + "\"toolName\":\"t2\",\"rendererKey\":\"t2\",\"argumentsJson\":\"{}\","
                    + "\"historyAction\":null,\"environmentName\":null}],"
                    + "\"error\":false,\"detailsJson\":\"{}\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c\","
                    + "\"toolName\":\"t\",\"rendererKey\":\"t\","
                    + "\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c2\","
                    + "\"toolName\":\"t2\",\"rendererKey\":\"t2\","
                    + "\"contents\":[{\"type\":\"text\",\"text\":\"x\"}],"
                    + "\"error\":false,\"detailsJson\":\"{}\"}],\"error\":false,\"detailsJson\":\"{}\"}]}"));
  }

  @Test
  void rejectsRoleContentMismatchesAndWrongTypedToolResultFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"USER\",\"contents\":[{\"type\":\"tool_call\",\"toolCallId\":\"c\","
                    + "\"toolName\":\"t\",\"rendererKey\":\"t\",\"argumentsJson\":\"{}\","
                    + "\"historyAction\":null,\"environmentName\":null}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c\","
                    + "\"toolName\":\"t\",\"rendererKey\":\"t\","
                    + "\"contents\":[{\"type\":\"text\",\"text\":\"x\"}],"
                    + "\"error\":\"no\",\"detailsJson\":\"{}\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"c\","
                    + "\"toolName\":\"t\",\"rendererKey\":\"t\",\"contents\":{},"
                    + "\"error\":false,\"detailsJson\":\"{}\"}]}"));
  }
}

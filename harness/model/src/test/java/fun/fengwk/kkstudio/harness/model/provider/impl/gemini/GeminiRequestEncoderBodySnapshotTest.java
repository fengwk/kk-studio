package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 断言完整请求体的逐字节输出。
 *
 * <p>逐字段断言无法发现顺序变化，因此这里锁定整段 UTF-8 文本；Gemini thinking wire contract 的有意变化同步更新对应期望值，其余请求字段保持稳定。
 */
class GeminiRequestEncoderBodySnapshotTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String SYSTEM_INSTRUCTION = "Test system instruction.";

  private final GeminiRequestEncoder encoder = new GeminiRequestEncoder();

  private static ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        "google-test",
        ProviderType.GOOGLE,
        "https://generativelanguage.googleapis.com/v1beta",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
  }

  /** 覆盖 tools、thinkingConfig、媒体、tool_result、原生 replay 与 opaque part 的完整请求体。 */
  @Test
  void encodesFullBodyByteForByte() {
    ProviderRequest request =
        new ProviderRequest(
            model(true),
            new ModelVariant("default", "high", PromptCacheRetention.NONE, null),
            4096,
            SYSTEM_INSTRUCTION,
            List.of(
                userWithMedia(), assistantWithNativeParts(), toolResult(), userText("follow up")),
            List.of(tool("read_file", "Read a file from disk"), tool("write_file", "Write a file")),
            ProviderCacheControl.none());

    String body =
        new String(encoder.encode(request, descriptor()).bodyUtf8Bytes(), StandardCharsets.UTF_8);

    assertEquals(EXPECTED_FULL_BODY, body);
  }

  /** 覆盖原生协议选项合并、hosted tool 保留与 reasoning off 的完整请求体。 */
  @Test
  void encodesNativeOptionsAndReasoningOffByteForByte() {
    ObjectNode options = NODES.objectNode();
    options.putObject("generationConfig").put("temperature", 0.25).put("topP", 0.9);
    options
        .putArray("tools")
        .addObject()
        .putObject("googleSearch")
        .put("dynamicRetrievalConfig", true);

    ProviderRequest request =
        new ProviderRequest(
            model(true),
            new ModelVariant(
                "default",
                "off",
                PromptCacheRetention.NONE,
                new ProviderProtocolOptions(options.toString())),
            2048,
            SYSTEM_INSTRUCTION,
            List.of(userText("hello")),
            List.of(),
            ProviderCacheControl.none());

    String body =
        new String(encoder.encode(request, descriptor()).bodyUtf8Bytes(), StandardCharsets.UTF_8);

    assertEquals(EXPECTED_NATIVE_OPTIONS_BODY, body);
  }

  private ProviderMessage userWithMedia() {
    return new ProviderMessage(
        ProviderMessageRole.USER,
        List.of(
            new ProviderTextBlock("look"),
            new ProviderImageBlock("image/png", "data:image/png;base64,AQIDBA=="),
            new ProviderAudioBlock("audio/wav", "gs://bucket/a.wav")));
  }

  private ProviderMessage assistantWithNativeParts() {
    ProviderBlockData thoughtData =
        new ProviderBlockData(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            "sig-1",
            null,
            null,
            null,
            null,
            false,
            true,
            NODES.objectNode());

    return new ProviderMessage(
        ProviderMessageRole.ASSISTANT,
        List.of(
            new ProviderThinkingBlock("plan", thoughtData),
            new ProviderTextBlock("answer"),
            new ProviderToolCallBlock(
                new ProviderToolCall("call_0", "read_file", "{\"path\":\"a\"}")),
            new ProviderOpaqueBlock("gemini_part", opaqueData())),
        "high");
  }

  private ProviderBlockData opaqueData() {
    return new ProviderBlockData(
        ProviderReplayFormat.GEMINI_CONTENT,
        descriptor().affinity("gemini-2.5-flash"),
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        NODES.objectNode());
  }

  private static ProviderMessage toolResult() {
    return new ProviderMessage(
        ProviderMessageRole.TOOL,
        List.of(
            new ProviderToolResultBlock(
                "call_0",
                "read_file",
                List.of(
                    new ProviderTextBlock("file body"),
                    new ProviderJsonBlock("{\"ok\":true}"),
                    new ProviderDocumentBlock(
                        "application/pdf", "data:application/pdf;base64,AQID")),
                false,
                "{}")));
  }

  private static ProviderMessage userText(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderToolDefinition tool(String name, String description) {
    return new ProviderToolDefinition(name, description, "{\"type\":\"object\",\"properties\":{}}");
  }

  private static ModelDescriptor model(boolean reasoning) {
    return new ModelDescriptor(
        "google-test",
        "gemini-2.5-flash",
        "gemini-2.5-flash",
        Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE),
        true,
        reasoning);
  }

  private static final String EXPECTED_FULL_BODY =
      "{\"systemInstruction\":{\"parts\":[{\"text\":\"Test system instruction.\"}]},\"generationConfig\":{\"maxOutputTokens\":409"
          + "6,\"thinkingConfig\":{\"includeThoughts\":true,\"thinkingBudget\":24576}},\"tools\":[{\"functionDeclarations\":[{\"name\":"
          + "\"read_file\",\"description\":\"Read a file from disk\",\"parameters\":{\"type\":\"object\",\"properties\":{}}},{\"name\":\"wri"
          + "te_file\",\"description\":\"Write a file\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}]}],\"contents\":[{\"role\":\""
          + "user\",\"parts\":[{\"text\":\"look\"},{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AQIDBA==\"}},{\"fileData\":{\"mimeTyp"
          + "e\":\"audio/wav\",\"fileUri\":\"gs://bucket/a.wav\"}}]},{\"role\":\"model\",\"parts\":[{\"text\":\"plan\"},{\"text\":\"answer\"},{\""
          + "functionCall\":{\"name\":\"read_file\",\"id\":\"call_0\",\"args\":{\"path\":\"a\"}}}]},{\"role\":\"user\",\"parts\":[{\"functionResp"
          + "onse\":{\"name\":\"read_file\",\"id\":\"call_0\",\"response\":{\"results\":[\"file body\",{\"ok\":true}]},\"parts\":[{\"inlineData"
          + "\":{\"mimeType\":\"application/pdf\",\"data\":\"AQID\"}}]}},{\"text\":\"follow up\"}]}]}";

  private static final String EXPECTED_NATIVE_OPTIONS_BODY =
      "{\"generationConfig\":{\"temperature\":0.25,\"topP\":0.9,\"maxOutputTokens\":2048,\"thinkingConfig\":{\"includeThoughts\":"
          + "false,\"thinkingBudget\":0}},\"tools\":[{\"googleSearch\":{\"dynamicRetrievalConfig\":true}}],\"systemInstruction\":{\"pa"
          + "rts\":[{\"text\":\"Test system instruction.\"}]},\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hello\"}]}]}";
}

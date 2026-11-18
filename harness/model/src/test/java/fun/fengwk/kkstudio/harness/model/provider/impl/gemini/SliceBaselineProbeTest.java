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
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基准核对：把 Gemini 编码器与流式累积器在代表性输入上的输出与逐字节期望值比对。
 *
 * <p>期望文件 {@code slice-baseline-gemini.txt} 覆盖工具、thinkingConfig、变体原生选项、图片/音频/文档块、assistant
 * 原生回放与工具结果，以及三组流式事件序列的终态响应、原始 usage 与错误顺序。已确认的 Gemini wire 行为变更同步更新相应期望值。
 */
class SliceBaselineProbeTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String SYSTEM = "Test system instruction.";

  @Test
  void matchesTheExpectedWireOutputByteForByte() throws IOException {
    String golden;
    try (InputStream in = getClass().getResourceAsStream("slice-baseline-gemini.txt")) {
      golden = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertEquals(golden, dump(), "probe output");
  }

  private String dump() {
    StringBuilder out = new StringBuilder();
    encoderCase(out, "tools-thinking-replay", fullRequest());
    encoderCase(out, "native-options-reasoning-off", nativeOptionsRequest());
    encoderCase(out, "plain", plainRequest());
    streamCase(out, "stream-text-tool-usage", textToolEvents());
    streamCase(out, "stream-incremental", incrementalEvents());
    streamCase(out, "stream-blocked", blockedEvents());
    return out.toString();
  }

  private void encoderCase(StringBuilder out, String name, ProviderRequest request) {
    out.append("== encoder ").append(name).append('\n');
    try {
      out.append(
          new String(
              new GeminiRequestEncoder().encode(request, descriptor()).bodyUtf8Bytes(),
              StandardCharsets.UTF_8));
    } catch (ProviderException error) {
      out.append("error ").append(error.kind()).append(' ').append(error.getMessage());
    }
    out.append('\n');
  }

  private void streamCase(StringBuilder out, String name, List<String> events) {
    out.append("== stream ").append(name).append('\n');
    List<String> handled = new ArrayList<>();
    ProviderStreamBridge bridge =
        new ProviderStreamBridge(
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event) {
                handled.add("event " + event.getClass().getSimpleName());
              }

              @Override
              public void onComplete(ProviderCompletion completion) {}

              @Override
              public void onError(ProviderException error) {
                handled.add("error " + error.kind() + " " + error.getMessage());
              }
            });
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(plainRequest(), descriptor(), bridge);
    for (String event : events) {
      try {
        accumulator.handleEvent(event);
      } catch (ProviderException error) {
        out.append("handleError ")
            .append(error.kind())
            .append(' ')
            .append(error.getMessage())
            .append('\n');
      }
    }
    try {
      ProviderCompletion completion = accumulator.finish();
      out.append(new ProviderResponseJsonCodec().encode(completion.response()));
    } catch (ProviderException error) {
      out.append("finishError ").append(error.kind()).append(' ').append(error.getMessage());
    }
    out.append('\n');
    for (String event : handled) {
      out.append(event).append('\n');
    }
  }

  private static ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        "google-test",
        ProviderType.GOOGLE,
        "https://generativelanguage.googleapis.com/v1beta",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
  }

  private ProviderRequest fullRequest() {
    return new ProviderRequest(
        model(true),
        new ModelVariant("default", "high", PromptCacheRetention.NONE, null),
        4096,
        SYSTEM,
        List.of(userWithMedia(), assistantWithNativeParts(), toolResult(), userText("follow up")),
        List.of(tool("read_file", "Read a file from disk"), tool("write_file", "Write a file")),
        ProviderCacheControl.none());
  }

  private ProviderRequest nativeOptionsRequest() {
    ObjectNode options = NODES.objectNode();
    options.putObject("generationConfig").put("temperature", 0.25).put("topP", 0.9);
    options
        .putArray("tools")
        .addObject()
        .putObject("googleSearch")
        .put("dynamicRetrievalConfig", true);
    return new ProviderRequest(
        model(true),
        new ModelVariant(
            "default",
            "off",
            PromptCacheRetention.NONE,
            new ProviderProtocolOptions(options.toString())),
        2048,
        SYSTEM,
        List.of(userText("hello")),
        List.of(),
        ProviderCacheControl.none());
  }

  private ProviderRequest plainRequest() {
    return new ProviderRequest(
        model(false),
        new ModelVariant("default", null, PromptCacheRetention.NONE, null),
        1024,
        SYSTEM,
        List.of(userText("hi")),
        List.of(),
        ProviderCacheControl.none());
  }

  private static List<String> textToolEvents() {
    return List.of(
        "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
            + "{\"text\":\"answer\"}]},\"index\":0}],\"usageMetadata\":"
            + "{\"promptTokenCount\":5,\"candidatesTokenCount\":7,\"totalTokenCount\":12}}",
        "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
            + "{\"functionCall\":{\"name\":\"read_file\",\"args\":{\"path\":\"a\"}}}]},"
            + "\"index\":0}],\"usageMetadata\":{\"promptTokenCount\":5,"
            + "\"candidatesTokenCount\":9,\"totalTokenCount\":14,"
            + "\"thoughtsTokenCount\":2}}",
        "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[]},"
            + "\"finishReason\":\"STOP\",\"index\":0}],\"usageMetadata\":"
            + "{\"promptTokenCount\":5,\"candidatesTokenCount\":9,\"totalTokenCount\":14,"
            + "\"thoughtsTokenCount\":2,\"cachedContentTokenCount\":3}}");
  }

  private static List<String> incrementalEvents() {
    return List.of(
        "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
            + "{\"text\":\"hel\"}]},\"index\":0}],\"usageMetadata\":"
            + "{\"promptTokenCount\":1,\"candidatesTokenCount\":1,\"totalTokenCount\":2}}",
        "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
            + "{\"text\":\"lo\"}]},\"index\":0}],\"usageMetadata\":"
            + "{\"promptTokenCount\":1,\"candidatesTokenCount\":2,\"totalTokenCount\":3}}",
        "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[]},"
            + "\"finishReason\":\"MAX_TOKENS\",\"index\":0}],\"usageMetadata\":"
            + "{\"promptTokenCount\":1,\"candidatesTokenCount\":2,\"totalTokenCount\":3}}");
  }

  private static List<String> blockedEvents() {
    return List.of(
        "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[]},"
            + "\"finishReason\":\"SAFETY\",\"index\":0}],\"promptFeedback\":"
            + "{\"blockReason\":\"SAFETY\"},\"usageMetadata\":{\"promptTokenCount\":4,"
            + "\"candidatesTokenCount\":0,\"totalTokenCount\":4}}");
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
}

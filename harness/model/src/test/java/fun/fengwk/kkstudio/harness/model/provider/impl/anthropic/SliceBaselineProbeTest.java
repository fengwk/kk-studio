package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

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
 * 基准核对：把 Anthropic 编码器与流式累积器在代表性输入上的输出与基准提交的产物逐字节比对。
 *
 * <p>期望文件 {@code slice-baseline-anthropic.txt} 由同一份探针代码在基准提交 {@code b79652fa4} 的独立 worktree 中生成，
 * 覆盖工具、缓存控制各档位、thinking/effort、变体原生选项、图片/文档块、assistant 原生回放与工具结果，以及三组流式事件序列的终态响应、 错误顺序与增量事件序列。
 */
class SliceBaselineProbeTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String SYSTEM = "Test system instruction.";

  @Test
  void matchesTheBaselineCommitOutputByteForByte() throws IOException {
    String golden;
    try (InputStream in = getClass().getResourceAsStream("slice-baseline-anthropic.txt")) {
      golden = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertEquals(golden, dump(), "probe output");
  }

  private String dump() {
    StringBuilder out = new StringBuilder();
    encoderCase(out, "tools-cache-short-replay", fullRequest());
    encoderCase(out, "native-options-long", nativeOptionsRequest());
    encoderCase(out, "cache-none-plain", plainRequest());
    encoderCase(out, "effort-off-image", effortOffRequest());
    streamCase(out, "stream-thinking-text-tool", thinkingTextToolEvents());
    streamCase(out, "stream-cache-breakdown-mismatch", cacheMismatchEvents());
    streamCase(out, "stream-blocked", blockedEvents());
    return out.toString();
  }

  private void encoderCase(StringBuilder out, String name, ProviderRequest request) {
    out.append("== encoder ").append(name).append('\n');
    try {
      out.append(
          new String(
              new AnthropicRequestEncoder().encode(request, descriptor()).bodyUtf8Bytes(),
              StandardCharsets.UTF_8));
    } catch (ProviderException error) {
      out.append("error ").append(error.kind()).append(' ').append(error.getMessage());
    }
    out.append('\n');
  }

  private void streamCase(StringBuilder out, String name, List<String[]> events) {
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
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(plainRequest(), descriptor(), bridge);
    for (String[] event : events) {
      try {
        accumulator.handleEvent(event[0], event[1]);
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
        "test-anthropic",
        ProviderType.ANTHROPIC,
        "https://api.anthropic.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
  }

  private ProviderRequest fullRequest() {
    return new ProviderRequest(
        reasoningModel(),
        new ModelVariant("default", "high", PromptCacheRetention.SHORT, null),
        4096,
        SYSTEM,
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER,
                List.of(
                    new ProviderTextBlock("hello"),
                    new ProviderImageBlock("image/png", "data:image/png;base64,AQIDBA=="))),
            assistantWithNativeReplay(),
            toolResult(),
            new ProviderMessage(
                ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("late system")))),
        List.of(tool("read_file", "Read a file from disk"), tool("write_file", "Write a file")),
        ProviderCacheControl.session(
            PromptCacheRetention.SHORT, "session-7f3c9a2e-1b04-4d6a-9f21-8c5b6d7e9a10"));
  }

  private ProviderRequest nativeOptionsRequest() {
    ObjectNode options = NODES.objectNode();
    options.put("top_k", 40);
    options.putArray("stop_sequences").add("END");
    options.putObject("metadata").put("user_id", "u-1");
    options
        .putArray("tools")
        .addObject()
        .put("type", "web_search_20250305")
        .put("name", "web_search")
        .put("max_uses", 5);
    return new ProviderRequest(
        midConversationModel(),
        new ModelVariant(
            "default",
            "medium",
            PromptCacheRetention.LONG,
            new ProviderProtocolOptions(options.toString())),
        2048,
        SYSTEM,
        List.of(userText("first"), assistantPlain(), userText("second")),
        List.of(tool("read_file", "Read a file from disk")),
        ProviderCacheControl.session(PromptCacheRetention.LONG, "session-1"),
        true);
  }

  private ProviderRequest plainRequest() {
    return new ProviderRequest(
        plainModel(),
        new ModelVariant("default", null, PromptCacheRetention.NONE, null),
        1024,
        SYSTEM,
        List.of(userText("hi")),
        List.of(),
        ProviderCacheControl.none());
  }

  private ProviderRequest effortOffRequest() {
    return new ProviderRequest(
        plainModel(),
        new ModelVariant("off", null, PromptCacheRetention.NONE, null),
        512,
        SYSTEM,
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER,
                List.of(
                    new ProviderTextBlock("look"),
                    new ProviderImageBlock("image/jpeg", "data:image/jpeg;base64,AQID"),
                    new ProviderDocumentBlock(
                        "application/pdf", "data:application/pdf;base64,AQID")))),
        List.of(),
        ProviderCacheControl.session(PromptCacheRetention.NONE, "session-x"));
  }

  private static List<String[]> thinkingTextToolEvents() {
    return List.of(
        new String[] {
          "message_start",
          "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":10,"
              + "\"cache_read_input_tokens\":5,\"cache_creation_input_tokens\":20,"
              + "\"cache_creation\":{\"ephemeral_5m\":12,\"ephemeral_1h\":8},"
              + "\"server_tool_use\":{\"web_search_requests\":1}}}}"
        },
        new String[] {
          "content_block_start",
          "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
              + "{\"type\":\"thinking\",\"thinking\":\"Let me \"}}"
        },
        new String[] {
          "content_block_delta",
          "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
              + "{\"type\":\"thinking_delta\",\"thinking\":\"look\"}}"
        },
        new String[] {
          "content_block_delta",
          "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
              + "{\"type\":\"signature_delta\",\"signature\":\"sig\"}}"
        },
        new String[] {"content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"},
        new String[] {
          "content_block_start",
          "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\"}}"
        },
        new String[] {
          "content_block_delta",
          "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":"
              + "{\"type\":\"text_delta\",\"text\":\"answer\"}}"
        },
        new String[] {
          "content_block_delta",
          "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":"
              + "{\"type\":\"citations_delta\",\"citation\":{\"type\":\"page\",\"url\":\"u\"}}}"
        },
        new String[] {"content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}"},
        new String[] {
          "content_block_start",
          "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":"
              + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{}}}"
        },
        new String[] {
          "content_block_delta",
          "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":"
              + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}"
        },
        new String[] {
          "content_block_delta",
          "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":"
              + "{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"a\\\"}\"}}"
        },
        new String[] {"content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}"},
        new String[] {
          "message_delta",
          "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
              + "\"usage\":{\"output_tokens\":42,\"output_tokens_details\":{\"thinking_tokens\":7}}}"
        },
        new String[] {"message_stop", "{\"type\":\"message_stop\"}"});
  }

  private static List<String[]> cacheMismatchEvents() {
    return List.of(
        new String[] {
          "message_start",
          "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\",\"usage\":"
              + "{\"input_tokens\":1,\"cache_creation_input_tokens\":9,"
              + "\"cache_creation\":{\"ephemeral_5m\":1,\"ephemeral_1h\":1}}}}"
        },
        new String[] {
          "message_delta",
          "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
              + "\"usage\":{\"output_tokens\":2}}"
        },
        new String[] {"message_stop", "{\"type\":\"message_stop\"}"});
  }

  private static List<String[]> blockedEvents() {
    return List.of(
        new String[] {
          "message_start",
          "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_3\",\"usage\":"
              + "{\"input_tokens\":3,\"output_tokens\":0}}}"
        },
        new String[] {
          "message_delta",
          "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"},"
              + "\"usage\":{\"output_tokens\":0}}"
        },
        new String[] {"message_stop", "{\"type\":\"message_stop\"}"});
  }

  private ProviderMessage assistantWithNativeReplay() {
    ObjectNode nativeBlock = NODES.objectNode();
    nativeBlock.put("type", "text");
    nativeBlock.put("text", "replayed answer");
    nativeBlock.set("citations", NODES.arrayNode());

    ProviderBlockData data =
        new ProviderBlockData(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor().affinity("claude-sonnet-4-5"),
            null,
            null,
            null,
            NODES.objectNode());

    return new ProviderMessage(
        ProviderMessageRole.ASSISTANT,
        List.of(
            new ProviderThinkingBlock("plan", thinkingData()),
            new ProviderTextBlock("replayed answer", data),
            new ProviderToolCallBlock(
                new ProviderToolCall("toolu_01", "read_file", "{\"path\":\"a\"}")),
            new ProviderOpaqueBlock(
                "server_tool_use",
                new ProviderBlockData(
                    ProviderReplayFormat.ANTHROPIC_MESSAGES,
                    descriptor().affinity("claude-sonnet-4-5"),
                    null,
                    null,
                    null,
                    NODES.objectNode()))),
        "high");
  }

  private ProviderBlockData thinkingData() {
    return new ProviderBlockData(
        ProviderReplayFormat.ANTHROPIC_MESSAGES,
        descriptor().affinity("claude-sonnet-4-5"),
        "sig-abc",
        null,
        null,
        NODES.objectNode());
  }

  private static ProviderMessage assistantPlain() {
    return new ProviderMessage(
        ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("plain answer")), "medium");
  }

  private static ProviderMessage userText(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderMessage toolResult() {
    return new ProviderMessage(
        ProviderMessageRole.TOOL,
        List.of(
            new ProviderToolResultBlock(
                "toolu_01",
                "read_file",
                List.of(
                    new ProviderTextBlock("file body"),
                    new ProviderJsonBlock("{\"ok\":true}"),
                    new ProviderDocumentBlock(
                        "application/pdf", "data:application/pdf;base64,AQID")),
                false,
                "{}")));
  }

  private static ProviderToolDefinition tool(String name, String description) {
    return new ProviderToolDefinition(name, description, "{\"type\":\"object\",\"properties\":{}}");
  }

  private static ModelDescriptor reasoningModel() {
    return new ModelDescriptor(
        "test-anthropic",
        "claude-sonnet-4-5",
        "claude-sonnet-4-5",
        Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
        true,
        true);
  }

  private static ModelDescriptor midConversationModel() {
    return new ModelDescriptor(
        "test-anthropic",
        "claude-sonnet-4-5",
        "claude-sonnet-4-5",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        true,
        true);
  }

  private static ModelDescriptor plainModel() {
    return new ModelDescriptor(
        "test-anthropic",
        "claude-3-5-sonnet",
        "claude-3-5-sonnet",
        Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
        true,
        false);
  }
}

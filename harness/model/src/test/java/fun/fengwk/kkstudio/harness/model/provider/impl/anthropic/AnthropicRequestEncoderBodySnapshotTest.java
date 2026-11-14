package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
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
 * <p>编码器被重组为同包协作者时，JSON 字段写入顺序、判断顺序与错误抛出顺序都必须保持不变；逐字段断言无法发现顺序变化，因此这里锁定整段 UTF-8
 * 文本。期望值是在重构前的基准提交上用同一组输入运行编码器得到的输出。
 */
class AnthropicRequestEncoderBodySnapshotTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String SYSTEM_INSTRUCTION = "Test system instruction.";

  private final AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();
  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "test-anthropic",
          ProviderType.ANTHROPIC,
          "https://api.anthropic.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));

  /** 覆盖 tools + cache_control + thinking/effort + text/thinking/tool_use/tool_result 的完整请求体。 */
  @Test
  void encodesFullBodyByteForByte() {
    ProviderRequest request =
        new ProviderRequest(
            reasoningModel(),
            new ModelVariant("default", "high", PromptCacheRetention.SHORT, null),
            4096,
            SYSTEM_INSTRUCTION,
            List.of(
                userText("hello"),
                assistantWithNativeReplay(),
                toolResult(),
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("late system")))),
            List.of(tool("read_file", "Read a file from disk"), tool("write_file", "Write a file")),
            ProviderCacheControl.session(
                PromptCacheRetention.SHORT, "session-7f3c9a2e-1b04-4d6a-9f21-8c5b6d7e9a10"));

    String body =
        new String(encoder.encode(request, descriptor).bodyUtf8Bytes(), StandardCharsets.UTF_8);

    assertEquals(EXPECTED_FULL_BODY, body);
  }

  /** 覆盖原生协议选项合并、LONG 留存与 deferred tool lane 的完整请求体。 */
  @Test
  void encodesNativeOptionsAndLongRetentionByteForByte() {
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

    ProviderRequest request =
        new ProviderRequest(
            midConversationModel(),
            new ModelVariant(
                "default",
                "medium",
                PromptCacheRetention.LONG,
                new ProviderProtocolOptions(options.toString())),
            2048,
            SYSTEM_INSTRUCTION,
            List.of(userText("first"), assistantPlain(), userText("second")),
            List.of(tool("read_file", "Read a file from disk")),
            ProviderCacheControl.session(PromptCacheRetention.LONG, "session-1"),
            true);

    String body =
        new String(encoder.encode(request, descriptor).bodyUtf8Bytes(), StandardCharsets.UTF_8);

    assertEquals(EXPECTED_NATIVE_OPTIONS_BODY, body);
  }

  private ProviderMessage assistantWithNativeReplay() {
    ObjectNode nativeBlock = NODES.objectNode();
    nativeBlock.put("type", "text");
    nativeBlock.put("text", "replayed answer");
    nativeBlock.set("citations", NODES.arrayNode());

    ProviderBlockData data =
        new ProviderBlockData(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-sonnet-4-5"),
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
                new ProviderToolCall("toolu_01", "read_file", "{\"path\":\"a\"}"))),
        "high");
  }

  private ProviderBlockData thinkingData() {
    return new ProviderBlockData(
        ProviderReplayFormat.ANTHROPIC_MESSAGES,
        descriptor.affinity("claude-sonnet-4-5"),
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

  private static final String EXPECTED_FULL_BODY =
      "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":4096,\"stream\":true,\"thinking\":{\"type\":\"adaptive\",\"display\":\"summariz"
          + "ed\"},\"output_config\":{\"effort\":\"high\"},\"tools\":[{\"name\":\"read_file\",\"description\":\"Read a file from disk\",\"inp"
          + "ut_schema\":{\"type\":\"object\",\"properties\":{}}},{\"name\":\"write_file\",\"description\":\"Write a file\",\"input_schema\""
          + ":{\"type\":\"object\",\"properties\":{}},\"cache_control\":{\"type\":\"ephemeral\"}}],\"system\":[{\"type\":\"text\",\"text\":\"Tes"
          + "t system instruction.\",\"cache_control\":{\"type\":\"ephemeral\"}}],\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"t"
          + "ext\",\"text\":\"hello\"}]},{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"plan\"},{\"type\":\"text\",\"text\":\"rep"
          + "layed answer\"},{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"read_file\",\"input\":{\"path\":\"a\"}}]},{\"role\":\"user\",\"c"
          + "ontent\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_01\",\"content\":[{\"type\":\"text\",\"text\":\"file body\"},{\"type\":"
          + "\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"AQID\"}}],\"cache_control\":{\"type\":\""
          + "ephemeral\"}}]},{\"role\":\"system\",\"content\":[{\"type\":\"text\",\"text\":\"late system\",\"cache_control\":{\"type\":\"epheme"
          + "ral\"}}]}]}";

  private static final String EXPECTED_NATIVE_OPTIONS_BODY =
      "{\"top_k\":40,\"stop_sequences\":[\"END\"],\"metadata\":{\"user_id\":\"u-1\"},\"tools\":[{\"type\":\"web_search_20250305\",\"name"
          + "\":\"web_search\",\"max_uses\":5},{\"name\":\"read_file\",\"description\":\"Read a file from disk\",\"input_schema\":{\"type\":"
          + "\"object\",\"properties\":{}},\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}},{\"name\":\"__kk_deferred_placeholder_"
          + "_\",\"description\":\"Reserved placeholder. Never available. Never call this.\",\"input_schema\":{\"type\":\"object\",\"pr"
          + "operties\":{},\"required\":[]},\"defer_loading\":true}],\"model\":\"claude-sonnet-4-5\",\"max_tokens\":2048,\"stream\":true"
          + ",\"thinking\":{\"type\":\"adaptive\",\"display\":\"summarized\",\"block_binding\":{\"prefix_mismatch_behavior\":\"drop_block\""
          + "}},\"output_config\":{\"effort\":\"medium\"},\"system\":[{\"type\":\"text\",\"text\":\"Test system instruction.\",\"cache_contr"
          + "ol\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}}],\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"first\",\"cac"
          + "he_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}}]},{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"plain ans"
          + "wer\"}]},{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"second\",\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h"
          + "\"}}]}]}";
}

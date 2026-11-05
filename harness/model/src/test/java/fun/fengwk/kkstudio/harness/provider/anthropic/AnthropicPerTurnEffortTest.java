package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 仅通过本地编码器验证 effort 控制，不调用模型。 */
class AnthropicPerTurnEffortTest {
  private static final String BETA = "per-turn-control-2026-07-01";
  private static final String MID = "mid-conversation-system-2026-04-07";
  private static final String EFFORT = "effort-2025-11-24";
  private static final List<String> AUTOMATIC_BETAS = List.of(MID, BETA, EFFORT);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final ProviderDescriptor DESCRIPTOR =
      new ProviderDescriptor(
          "p",
          ProviderType.ANTHROPIC,
          "https://example.invalid/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1, 2));
  private static final ModelDescriptor MODEL =
      new ModelDescriptor("p", "model", "wire", Set.of(ModelInputModality.TEXT), true, true);
  private static final AnthropicConfiguration DEFAULT_CONFIG = AnthropicConfiguration.defaults();

  // 如果已有中途 system，复用该节点，不能覆盖它既有的 output_config 子字段。
  @Test
  void reusesExistingSystemControl() {
    for (boolean existingConfig : List.of(false, true)) {
      ArrayNode input = MAPPER.createArrayNode();
      input.addObject().put("role", "user").putArray("content");
      ObjectNode system = input.addObject();
      system.put("role", "system");
      system.putArray("content");
      if (existingConfig) {
        system.putObject("output_config").put("other", 7);
      }
      ArrayNode output = AnthropicRequestEncoder.applyPerTurnEffort(input, List.of("", ""), "max");
      assertEquals(2, output.size());
      assertEquals("max", output.get(1).path("output_config").path("effort").asString());
      if (existingConfig) {
        assertEquals(7, output.get(1).path("output_config").path("other").asInt());
      }
    }
  }

  // 首轮已有 max 声明；后续不变时重传同一前缀，不追加重复声明。
  @Test
  void keepsTheFirstDeclarationStableAcrossUnchangedTurns() {
    JsonNode first = body(encode(DEFAULT_CONFIG, "max", List.of(user("u1"))));
    JsonNode second =
        body(encode(DEFAULT_CONFIG, "max", List.of(user("u1"), assistant("max"), user("u2"))));
    assertEquals("max", first.path("output_config").path("effort").asString());
    assertEquals("max", second.path("output_config").path("effort").asString());
    assertEquals(List.of("user", "system:max"), describe(first.path("messages")));
    assertEquals(
        List.of("user", "system:max", "assistant", "user"), describe(second.path("messages")));
    assertEquals(first.path("messages").get(0), second.path("messages").get(0));
    assertEquals(first.path("messages").get(1), second.path("messages").get(1));
    assertEquals(
        MAPPER.readTree(
            "{\"role\":\"system\",\"content\":[],\"output_config\":{\"effort\":\"max\"}}"),
        first.path("messages").get(1));
  }

  // 对比官方 .280 样本抽取的 effort 子树，而不是只对照实现生成的角色列表。
  @Test
  void firstDeclarationMatchesNormalizedNativeSample() throws Exception {
    JsonNode wire = body(encode(DEFAULT_CONFIG, "max", List.of(user("u1"))));
    ObjectNode normalized = MAPPER.createObjectNode();
    normalized.set("root_output_config", wire.path("output_config"));
    ObjectNode control = normalized.putArray("messages_output_config_entries").addObject();
    control.put("index", 1);
    control.set("role", wire.path("messages").get(1).path("role"));
    control.set("output_config_raw", wire.path("messages").get(1).path("output_config"));
    try (InputStream input =
        AnthropicPerTurnEffortTest.class.getResourceAsStream(
            "/fun/fengwk/kkstudio/harness/provider/anthropic/effort-max-280-normalized.json")) {
      assertEquals(MAPPER.readTree(input), normalized);
    }
  }

  // 缺失值夹在 max/high 之间时必须继承后面的 high，不能错误地继承前面的 max。
  @Test
  void declaresOnlyChangesAndBackfillsUnknown() {
    var encoded =
        encode(
            DEFAULT_CONFIG,
            "max",
            List.of(
                user("u1"),
                assistant("max"),
                user("u2"),
                assistant(null),
                user("u3"),
                assistant("high"),
                user("u4")));
    JsonNode root = body(encoded);
    assertEquals("max", root.path("output_config").path("effort").asString());
    assertEquals(
        List.of(
            "user",
            "system:max",
            "assistant",
            "user",
            "system:high",
            "assistant",
            "user",
            "assistant",
            "user",
            "system:max"),
        describe(root.path("messages")));
    assertFalse(root.toString().contains("reasoningEffort"));
    assertEquals(0, root.path("messages").get(1).path("content").size());
    assertEquals(3, root.path("messages").get(1).size());
    assertEquals(AUTOMATIC_BETAS, encoded.betaFeatures());
  }

  // 多条 user 与 TOOL 都属于同一连续 user-like 段，不能在工具结果之间插入 system。
  @Test
  void keepsToolResultsAdjacentAndSkipsOffHistory() {
    ProviderMessage tool1 = tool("a");
    ProviderMessage tool2 = tool("b");
    JsonNode messages =
        body(encode(
                DEFAULT_CONFIG,
                "max",
                List.of(
                    user("first"), user("second"), assistant("off"), tool1, tool2, user("tail"))))
            .path("messages");
    assertEquals(
        List.of("user", "user", "system:max", "assistant", "user", "user", "user"),
        describe(messages));
  }

  // 不同思考语义不产生消息级控制；自定义 beta 只追加能力并去重。
  @Test
  void excludesOffUnsetBudgetAndNonReasoning() {
    List<ProviderMessage> messages = List.of(user("u"), assistant("high"), user("tail"));
    assertEquals(3, body(encode(DEFAULT_CONFIG, "off", messages)).path("messages").size());
    assertEquals(3, body(encode(DEFAULT_CONFIG, null, messages)).path("messages").size());
    var budget = new AnthropicConfiguration(AnthropicThinkingMode.BUDGET);
    assertEquals(3, body(encode(budget, "low", messages)).path("messages").size());
    ModelDescriptor noReasoning =
        new ModelDescriptor("p", "model", "wire", Set.of(ModelInputModality.TEXT), true, false);
    ProviderRequest nonReasoning =
        new ProviderRequest(
            noReasoning,
            new ModelVariant("v", "max"),
            20000,
            "system",
            messages,
            List.of(),
            ProviderCacheControl.none());
    assertEquals(
        3,
        body(new AnthropicRequestEncoder(DEFAULT_CONFIG).encode(nonReasoning, DESCRIPTOR))
            .path("messages")
            .size());
    var configured =
        new AnthropicConfiguration(
            AnthropicThinkingMode.ADAPTIVE, List.of("custom-2026-01-01", EFFORT, BETA));
    AnthropicEncodedRequest withExtras = encode(configured, "max", messages);
    assertEquals(List.of("custom-2026-01-01", EFFORT, BETA, MID), withExtras.betaFeatures());
    assertEquals(body(encode(DEFAULT_CONFIG, "max", messages)), body(withExtras));
  }

  // native compaction reset 后不允许已丢弃的旧 effort 或缓存端点影响新前缀。
  @Test
  void resetsEffortAndCachePrefixAtNativeCompaction() {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("role", "assistant");
    payload.putArray("content").addObject().put("type", "compaction");
    ProviderReplayState replay =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES, DESCRIPTOR.affinity("wire"), payload);
    ProviderMessage compacted =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("")), replay, null);
    // 空 durable 文本与 compaction replay 一致。
    var request =
        new ProviderRequest(
            MODEL,
            new ModelVariant("v", "max"),
            20000,
            "system",
            List.of(user("discard"), assistant("high"), compacted, user("retained")),
            List.of(),
            ProviderCacheControl.session(PromptCacheRetention.SHORT, "key"));
    JsonNode root = body(new AnthropicRequestEncoder(DEFAULT_CONFIG).encode(request, DESCRIPTOR));
    assertEquals(List.of("assistant", "user", "system:max"), describe(root.path("messages")));
    assertFalse(root.toString().contains("discard"));
    assertFalse(root.path("messages").get(2).toString().contains("cache_control"));
  }

  private static AnthropicEncodedRequest encode(
      AnthropicConfiguration config, String effort, List<ProviderMessage> messages) {
    ProviderRequest request =
        new ProviderRequest(
            MODEL,
            new ModelVariant("v", effort),
            20000,
            "system",
            messages,
            List.of(),
            ProviderCacheControl.none());
    return new AnthropicRequestEncoder(config).encode(request, DESCRIPTOR);
  }

  private static JsonNode body(AnthropicEncodedRequest encoded) {
    return MAPPER.readTree(encoded.bodyUtf8Bytes());
  }

  private static ProviderMessage user(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderMessage assistant(String effort) {
    return new ProviderMessage(
        ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("answer")), null, effort);
  }

  private static ProviderMessage tool(String id) {
    return new ProviderMessage(
        ProviderMessageRole.TOOL,
        List.of(
            new ProviderToolResultBlock(
                id, "tool", List.of(new ProviderTextBlock("ok")), false, null)));
  }

  private static List<String> describe(JsonNode messages) {
    ArrayList<String> roles = new ArrayList<>();
    for (JsonNode node : messages) {
      String role = node.path("role").asString();
      roles.add(
          role.equals("system")
              ? role + ":" + node.path("output_config").path("effort").asString()
              : role);
    }
    return roles;
  }
}

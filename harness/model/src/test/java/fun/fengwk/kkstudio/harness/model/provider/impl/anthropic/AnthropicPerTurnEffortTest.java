package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Anthropic 的消息级 effort 必须重放历史声明并保持早期请求前缀不变。 */
class AnthropicPerTurnEffortTest {
  private static final String OUTPUT_CONFIG_BETA = "mid-conversation-output-config-2026-07-01";
  private static final String BINDING_BETA = "thinking-binding-controls-2026-08-01";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final ProviderDescriptor PROVIDER =
      new ProviderDescriptor(
          "p",
          ProviderType.ANTHROPIC,
          "https://example.invalid/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
  private static final ModelDescriptor MESSAGE_LEVEL_MODEL =
      new ModelDescriptor(
          "p", "model", "wire", Set.of(ModelInputModality.TEXT), true, true, false, true);
  private static final ModelDescriptor MODEL_WITHOUT_MESSAGE_LEVEL_EFFORT =
      new ModelDescriptor("p", "model", "wire", Set.of(ModelInputModality.TEXT), true, true);

  @Test
  void keepsTheEarlierRequestMessagePrefixAndUsesTheFirstStoredReplyLevel() {
    JsonNode first = body(encode(MESSAGE_LEVEL_MODEL, "high", List.of(user("first"))));
    JsonNode next =
        body(
            encode(
                MESSAGE_LEVEL_MODEL,
                "low",
                List.of(
                    user("first"),
                    assistant("high"),
                    user("other-model turn"),
                    assistant(null),
                    user("second"))));

    assertEquals("high", first.path("output_config").path("effort").asString());
    assertEquals("high", next.path("output_config").path("effort").asString());
    assertEquals(first.path("messages").get(0), next.path("messages").get(0));
    assertEquals(
        List.of("user", "assistant", "user", "assistant", "user", "system:low"),
        describe(next.path("messages")));
    assertEquals(
        "low", next.path("messages").get(5).path("output_config").path("effort").asString());
    assertEquals(
        List.of(OUTPUT_CONFIG_BETA, BINDING_BETA),
        encode(MESSAGE_LEVEL_MODEL, "low", List.of(user("u"))).betaFeatures());
    assertEquals(
        "drop_block",
        next.path("thinking").path("block_binding").path("prefix_mismatch_behavior").asString());
  }

  @Test
  void requestBytesExtendEarlierTurnWhenEffortChangesAndCacheBreakpointMoves() throws Exception {
    List<ProviderMessage> history =
        List.of(
            user("first user"),
            assistant(null),
            user("second user"),
            assistant(null),
            user("third user"),
            assistant(null),
            user("fourth user"),
            assistant(null),
            user("request N"));
    ProviderCacheControl cacheControl =
        ProviderCacheControl.session(PromptCacheRetention.SHORT, "effort-prefix");
    byte[] requestN = encode(MESSAGE_LEVEL_MODEL, "low", history, cacheControl).bodyUtf8Bytes();

    List<ProviderMessage> nextHistory = new ArrayList<>(history);
    nextHistory.add(assistant("low"));
    nextHistory.add(user("request N+1"));
    byte[] requestNPlusOne =
        encode(MESSAGE_LEVEL_MODEL, "high", nextHistory, cacheControl).bodyUtf8Bytes();

    JsonNode before = MAPPER.readTree(requestN);
    JsonNode after = MAPPER.readTree(requestNPlusOne);
    assertTrue(before.path("messages").get(4).path("content").get(0).has("cache_control"));
    assertFalse(after.path("messages").get(4).path("content").get(0).has("cache_control"));
    assertBytesPrefixAfterRemovingMovingCacheBreakpoint(requestN, requestNPlusOne, 4);
  }

  @Test
  void keepsSystemMessageAndEffortChangeAtTheSamePositionPrefixStable() throws Exception {
    List<ProviderMessage> history =
        List.of(user("previous"), assistant("low"), system("system update"));
    byte[] requestN =
        encode(MESSAGE_LEVEL_MODEL, "high", history, ProviderCacheControl.none()).bodyUtf8Bytes();

    List<ProviderMessage> nextHistory = new ArrayList<>(history);
    nextHistory.add(assistant("high"));
    nextHistory.add(user("next"));
    byte[] requestNPlusOne =
        encode(MESSAGE_LEVEL_MODEL, "medium", nextHistory, ProviderCacheControl.none())
            .bodyUtf8Bytes();

    JsonNode before = MAPPER.readTree(requestN);
    JsonNode after = MAPPER.readTree(requestNPlusOne);
    assertEquals(
        "high", before.path("messages").get(2).path("output_config").path("effort").asString());
    assertEquals(
        "high", after.path("messages").get(2).path("output_config").path("effort").asString());
    assertEquals("assistant", after.path("messages").get(3).path("role").asString());
    assertBytesPrefix(requestN, requestNPlusOne);
  }

  @Test
  void insertsOnlySameModelLevelChangesBeforeTheirRepliesAndMergesSystemAtPosition() {
    ArrayNode messages = MAPPER.createArrayNode();
    messages.add(message("user"));
    ObjectNode system = messages.addObject();
    system.put("role", "system");
    system.putArray("content");
    system.putObject("output_config").put("cache", true);
    messages.add(message("assistant"));
    messages.add(message("assistant"));
    ArrayNode result =
        AnthropicRequestEncoder.applyMessageLevelEffort(
            messages, Arrays.asList(null, null, "low", null), "low", "high");

    assertEquals(4, result.size());
    assertEquals("low", result.get(1).path("output_config").path("effort").asString());
    assertTrue(result.get(1).path("output_config").path("cache").asBoolean());
    assertEquals("assistant", result.get(2).path("role").asString());
    assertEquals("assistant", result.get(3).path("role").asString());
  }

  @Test
  void disablesMessageMarkersAndBindingWhenThinkingIsOff() {
    AnthropicEncodedRequest encoded =
        encode(MESSAGE_LEVEL_MODEL, "off", List.of(user("u"), assistant("high"), user("tail")));
    JsonNode root = body(encoded);

    assertEquals("disabled", root.path("thinking").path("type").asString());
    assertFalse(root.path("thinking").has("block_binding"));
    assertFalse(root.toString().contains("output_config"));
    assertTrue(encoded.betaFeatures().isEmpty());
  }

  @Test
  void modelsWithoutCapabilityKeepCurrentTopLevelEffortAndDoNotBindThinking() {
    AnthropicEncodedRequest encoded =
        encode(
            MODEL_WITHOUT_MESSAGE_LEVEL_EFFORT,
            "low",
            List.of(user("u"), assistant("high"), user("tail")));
    JsonNode root = body(encoded);

    assertEquals("low", root.path("output_config").path("effort").asString());
    assertFalse(root.path("thinking").has("block_binding"));
    assertEquals(List.of(), encoded.betaFeatures());
    assertEquals(List.of("user", "assistant", "user"), describe(root.path("messages")));
  }

  @Test
  void declaresHistoricalLevelChangesAndTheTrailingCurrentLevel() {
    JsonNode root =
        body(
            encode(
                MESSAGE_LEVEL_MODEL,
                "medium",
                List.of(user("u1"), assistant("high"), user("u2"), assistant("low"), user("u3"))));

    assertEquals("high", root.path("output_config").path("effort").asString());
    assertEquals(
        List.of("user", "assistant", "user", "system:low", "assistant", "user", "system:medium"),
        describe(root.path("messages")));
  }

  private static AnthropicEncodedRequest encode(
      ModelDescriptor model, String effort, List<ProviderMessage> messages) {
    return encode(model, effort, messages, ProviderCacheControl.none());
  }

  private static AnthropicEncodedRequest encode(
      ModelDescriptor model,
      String effort,
      List<ProviderMessage> messages,
      ProviderCacheControl cacheControl) {
    ProviderRequest request =
        new ProviderRequest(
            model,
            new ModelVariant("v", effort, PromptCacheRetention.NONE, null),
            20000,
            "system",
            messages,
            List.of(),
            cacheControl);
    return new AnthropicRequestEncoder().encode(request, PROVIDER);
  }

  private static void assertBytesPrefix(byte[] before, byte[] after) {
    byte[] expectedPrefix = Arrays.copyOf(before, before.length - 2);
    assertArrayEquals(expectedPrefix, Arrays.copyOf(after, expectedPrefix.length));
  }

  private static void assertBytesPrefixAfterRemovingMovingCacheBreakpoint(
      byte[] before, byte[] after, int messageIndex) throws Exception {
    ObjectNode normalizedBefore = (ObjectNode) MAPPER.readTree(before);
    ObjectNode movedBlock =
        (ObjectNode) normalizedBefore.path("messages").get(messageIndex).path("content").get(0);
    assertTrue(movedBlock.has("cache_control"));
    movedBlock.remove("cache_control");

    JsonNode afterWire = MAPPER.readTree(after);
    assertFalse(
        afterWire.path("messages").get(messageIndex).path("content").get(0).has("cache_control"));
    assertBytesPrefix(MAPPER.writeValueAsBytes(normalizedBefore), after);
  }

  private static JsonNode body(AnthropicEncodedRequest encoded) {
    return MAPPER.readTree(encoded.bodyUtf8Bytes());
  }

  private static ProviderMessage user(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderMessage assistant(String effort) {
    return new ProviderMessage(
        ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("answer")), effort);
  }

  private static ProviderMessage system(String text) {
    return new ProviderMessage(ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock(text)));
  }

  private static ObjectNode message(String role) {
    ObjectNode message = MAPPER.createObjectNode();
    message.put("role", role);
    message.putArray("content");
    return message;
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

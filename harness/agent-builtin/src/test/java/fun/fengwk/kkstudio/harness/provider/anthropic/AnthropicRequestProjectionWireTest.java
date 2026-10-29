package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.codec.ProviderToolCallDiagnosticJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.BuiltinHistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Anthropic 请求编码与 Runtime 历史投影的集成契约：历史诊断与缓存端点必须经过真实投影后再编码，不能只在手工 {@link ProviderMessage} 上成立。 */
class AnthropicRequestProjectionWireTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String SYSTEM_INSTRUCTION = "Test system instruction.";

  private final AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();
  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "test-anthropic",
          ProviderType.ANTHROPIC,
          "https://api.anthropic.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1L, 2L));

  @Test
  void verifiesSemanticChainFromLengthDiagnosticToAssistantTextBlockInNextRound()
      throws IOException {
    // 1. 构造一个 LENGTH 导致的 ProviderResponse，包含 toolCallDiagnostic（因截断缺失完整参数，replayState 为 null）
    ProviderToolCallDiagnostic diagnostic =
        new ProviderToolCallDiagnostic(
            0,
            "call_weather",
            "get_weather",
            null,
            "tool call truncated before arguments received");

    ProviderResponse lengthResponse =
        new ProviderResponse(
            "I was trying to call weather but got cut off",
            "",
            List.of(),
            GenerationStopReason.LENGTH,
            new ModelUsage(10, 15, 0, 0, Map.of(), 0L, 25L),
            "msg_len_1",
            null,
            "{}",
            List.of(diagnostic));

    // 2. 使用 BuiltinHistoryPayloadMapper 将 ProviderResponse 投影为 Assistant MessagePayload
    BuiltinHistoryPayloadMapper mapper = new BuiltinHistoryPayloadMapper();
    MessagePayload assistantPayload = mapper.assistantPayload(lengthResponse, List.of());
    AgentMessage asstAgentMsg = assistantPayload.message();

    // 3. 使用 ProviderMessageProjector 将 Session 历史投影为 ProviderMessage
    AgentMessage userAgentMsg =
        new AgentMessage(
            AgentMessageRole.USER, List.of(new TextMessageContent("What is the weather?")));
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());
    List<ProviderMessage> projectedMessages =
        projector.project(List.of(userAgentMsg, asstAgentMsg));

    // 4. 构造下一轮的新请求并交由 AnthropicRequestEncoder 编码
    ProviderRequest nextRoundRequest =
        request(defaultVariant(), projectedMessages, List.of(), ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(nextRoundRequest, descriptor);
    assertNotNull(encoded);

    // 5. 断言下一轮不会报 INVALID_REQUEST，且 assistant diagnostic 被安全降级为普通 text block，文本严格等于 codec JSON
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    JsonNode assistantContent = root.path("messages").get(1).path("content");
    assertEquals(2, assistantContent.size());

    // content 0: text
    assertEquals("text", assistantContent.get(0).path("type").asString(""));
    assertEquals(
        "I was trying to call weather but got cut off",
        assistantContent.get(0).path("text").asString(""));

    // content 1: diagnostic JSON 物化为普通 text block
    assertEquals("text", assistantContent.get(1).path("type").asString(""));
    String diagnosticJsonText = assistantContent.get(1).path("text").asString("");
    ProviderToolCallDiagnosticJsonCodec codec = new ProviderToolCallDiagnosticJsonCodec();
    ProviderToolCallDiagnostic decodedDiag = codec.decode(diagnosticJsonText);
    assertEquals(diagnostic.callIndex(), decodedDiag.callIndex());
    assertEquals(diagnostic.id(), decodedDiag.id());
    assertEquals(diagnostic.name(), decodedDiag.name());
    assertEquals("", decodedDiag.partialArguments());
    assertEquals(diagnostic.message(), decodedDiag.message());
  }

  /** 意图：从真实 AgentMessage 投影，少量和超过 20 个独立块的增长都必须保留最近请求端点。 */
  @ParameterizedTest
  @MethodSource("cacheGrowthCases")
  void retainsPreviousRequestEndpointAcrossGrowth(int growth) throws IOException {
    List<AgentMessage> history = new ArrayList<>();
    history.add(new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("old"))));
    history.add(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("answer"))));
    List<TextMessageContent> additions = new ArrayList<>();
    for (int i = 0; i < growth; i++) {
      additions.add(new TextMessageContent("new-" + i));
    }
    history.add(new AgentMessage(AgentMessageRole.USER, new ArrayList<>(additions)));
    JsonNode wire = cacheWire(ProviderMessageProjector.byNames(Set.of()).project(history));
    // tools、system 与最近两个历史请求端点（User 的最后一个新增块、Assistant answer）共 4 个标记
    assertTrue(wire.path("tools").get(0).has("cache_control"));
    assertTrue(wire.path("system").get(0).has("cache_control"));
    assertTrue(cacheBlock(wire, 0, 0).has("cache_control"));
    assertTrue(cacheBlock(wire, 2, growth - 1).has("cache_control"));
    assertFalse(cacheBlock(wire, 1, 0).has("cache_control"));
    assertEquals(4, countCacheMarkers(wire));
  }

  private static Stream<Integer> cacheGrowthCases() {
    return Stream.of(1, 21, 25);
  }

  private JsonNode cacheWire(List<ProviderMessage> messages) throws IOException {
    return MAPPER.readTree(
        encoder
            .encode(
                request(
                    defaultVariant(),
                    messages,
                    List.of(new ProviderToolDefinition("calc", "calc", "{\"type\":\"object\"}")),
                    new ProviderCacheControl(PromptCacheRetention.LONG, "cache")),
                descriptor)
            .bodyUtf8Bytes());
  }

  private static JsonNode cacheBlock(JsonNode wire, int message, int block) {
    return wire.path("messages").get(message).path("content").get(block);
  }

  private static int countCacheMarkers(JsonNode node) {
    int count =
        node.isObject()
                && node.has("cache_control")
                && node.path("cache_control").path("type").asString("").equals("ephemeral")
            ? 1
            : 0;
    for (JsonNode child : node) {
      count += countCacheMarkers(child);
    }
    return count;
  }

  private static ProviderRequest request(
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    ModelDescriptor model =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-5-sonnet",
            "claude-3-5-sonnet",
            Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
            true,
            false);
    return new ProviderRequest(
        model, variant, 1024, SYSTEM_INSTRUCTION, messages, tools, cacheControl);
  }

  private static ModelVariant defaultVariant() {
    return new ModelVariant("default");
  }
}

package fun.fengwk.kkstudio.harness.builtin.provider.anthropic;

import static fun.fengwk.kkstudio.harness.builtin.ModelRequestSpecTestSupport.spec;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
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

import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.builtin.history.BuiltinHistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.request.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.codec.ProviderToolCallDiagnosticJsonCodec;
import fun.fengwk.kkstudio.harness.model.provider.impl.anthropic.AnthropicEncodedRequest;
import fun.fengwk.kkstudio.harness.model.provider.impl.anthropic.AnthropicRequestEncoder;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));

  @Test
  void verifiesSemanticChainFromLengthDiagnosticToAssistantTextBlockInNextRound()
      throws IOException {
    // 1. 构造一个 LENGTH 导致的 ProviderResponse，包含 toolCallDiagnostic（因截断缺失完整参数，block metadata 为空）
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
    ProviderMessageProjector projector = new ProviderMessageProjector();
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
    JsonNode wire = cacheWire(new ProviderMessageProjector().project(history));
    // tools、system 与最近两个历史请求端点（User 的最后一个新增块、Assistant answer）共 4 个标记
    assertTrue(wire.path("tools").get(0).has("cache_control"));
    assertTrue(wire.path("system").get(0).has("cache_control"));
    assertTrue(cacheBlock(wire, 0, 0).has("cache_control"));
    assertTrue(cacheBlock(wire, 2, growth - 1).has("cache_control"));
    assertFalse(cacheBlock(wire, 1, 0).has("cache_control"));
    assertEquals(4, countCacheMarkers(wire));
  }

  /**
   * 压缩后工具续作：新 assistant 的 thinking（带新前缀签名）与 tool_use 必须原样出现在 wire 上， 只有保留段里的旧 assistant 被移除
   * thinking。
   */
  @Test
  void compactionKeepsPostCompactionToolLoopThinkingOnTheWire() throws IOException {
    ProviderBlockData oldPrefixData =
        new ProviderBlockData(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            new ProviderReplayAffinity(
                ProviderType.ANTHROPIC, "test-anthropic", "claude-3-5-sonnet"),
            "signature-old-prefix",
            null,
            null,
            JsonMapper.builder().build().createObjectNode());
    ProviderBlockData newPrefixData =
        new ProviderBlockData(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            new ProviderReplayAffinity(
                ProviderType.ANTHROPIC, "test-anthropic", "claude-3-5-sonnet"),
            "signature-new-prefix",
            null,
            null,
            JsonMapper.builder().build().createObjectNode());

    List<Entry> entries = new ArrayList<>();
    entries.add(new Entry(id(1), SESSION_ID, null, new RootPayload(settings()), NOW));
    entries.add(turnStart(id(2), id(1), TurnStartReason.INPUT));
    entries.add(new Entry(id(3), SESSION_ID, id(2), user("summarized user"), NOW));
    entries.add(
        new Entry(
            id(4), SESSION_ID, id(3), assistantWithThinking("summarized", oldPrefixData), NOW));
    entries.add(turnEnd(id(5), id(4), id(2), false));
    // 保留段：切点之后的完整 turn，assistant 仍绑定旧前缀。
    entries.add(turnStart(id(6), id(5), TurnStartReason.INPUT));
    entries.add(new Entry(id(7), SESSION_ID, id(6), user("retained user"), NOW));
    entries.add(
        new Entry(id(8), SESSION_ID, id(7), assistantWithThinking("retained", oldPrefixData), NOW));
    entries.add(turnEnd(id(9), id(8), id(6), false));
    entries.add(
        new Entry(
            id(10),
            SESSION_ID,
            id(9),
            new TurnStartPayload(
                TurnStartReason.COMPACTION,
                settings(),
                THREAD_ID,
                100_000,
                16_384,
                CompactionStart.pending(CompactionTrigger.THRESHOLD, id(5)),
                null),
            NOW));
    entries.add(new Entry(id(11), SESSION_ID, id(10), new CompactionPayload("summary", null), NOW));
    entries.add(turnEnd(id(12), id(11), id(10), true));
    entries.add(turnStart(id(13), id(12), TurnStartReason.INPUT));
    entries.add(new Entry(id(14), SESSION_ID, id(13), user("new question"), NOW));
    entries.add(
        new Entry(
            id(15),
            SESSION_ID,
            id(14),
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ThinkingMessageContent("new thinking", newPrefixData),
                        new ToolCallMessageContent(
                            "call-1", "calc", "calc", "{}", null, newPrefixData))),
                new AssistantMessageMetadata(
                    GenerationStopReason.COMPLETE, new ModelUsage(1, 2, 0, 0, Map.of(), 0L, 3L)),
                null),
            NOW));

    ProviderRequest request =
        new ModelRequestMaterializer()
            .materialize(new EntryPath(entries), request(defaultVariant()));
    JsonNode wire = MAPPER.readTree(encoder.encode(request, descriptor).bodyUtf8Bytes());

    JsonNode messages = wire.path("messages");
    // summary wrapper + retained user + retained assistant(thinking 已移除) + new user + new
    // assistant(thinking 保留) + synthetic tool result
    assertEquals(6, messages.size());
    JsonNode retainedAssistant = messages.get(2).path("content");
    assertEquals("text", retainedAssistant.get(0).path("type").asString());
    JsonNode continuation = messages.get(4).path("content");
    assertEquals("thinking", continuation.get(0).path("type").asString());
    assertEquals("new thinking", continuation.get(0).path("thinking").asString());
    assertEquals("signature-new-prefix", continuation.get(0).path("signature").asString());
    assertEquals("tool_use", continuation.get(1).path("type").asString());
  }

  private static Entry turnStart(UUID id, UUID parent, TurnStartReason reason) {
    return new Entry(
        id, SESSION_ID, parent, new TurnStartPayload(reason, settings(), THREAD_ID), NOW);
  }

  private static Entry turnEnd(UUID id, UUID parent, UUID start, boolean continueModel) {
    return new Entry(
        id,
        SESSION_ID,
        parent,
        new TurnEndPayload(start, TurnEndOutcome.COMPLETED, continueModel, null, null),
        NOW);
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
                    ProviderCacheControl.session(PromptCacheRetention.LONG, "cache")),
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
    return new ModelVariant("default", null, PromptCacheRetention.NONE, null);
  }

  private static final UUID SESSION_ID = id(900);
  private static final UUID THREAD_ID = id(901);
  private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

  private static BranchSettings settings() {
    return new BranchSettings(
        "agent", BuiltinExecution.of(ModelSelection.model("claude-3-5-sonnet", "default")), null);
  }

  private static MessagePayload user(String text) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))), null, null);
  }

  private static MessagePayload assistantWithThinking(String text, ProviderBlockData data) {
    return new MessagePayload(
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new ThinkingMessageContent("thinking-" + text, data),
                new TextMessageContent(text))),
        new AssistantMessageMetadata(
            GenerationStopReason.COMPLETE, new ModelUsage(1, 2, 0, 0, Map.of(), 0L, 3L)),
        null);
  }

  /** 分支/普通请求统一 spec：只取 Provider 与模型身份，SYSTEM 与工具面由 EntryPath 决定。 */
  private static ModelRequestSpec request(ModelVariant variant) {
    return spec(
        ProviderType.ANTHROPIC,
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-5-sonnet",
            "claude-3-5-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            true,
            false),
        variant,
        1024,
        SYSTEM_INSTRUCTION,
        List.of(),
        List.of());
  }
}

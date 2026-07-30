package fun.fengwk.kkstudio.core.ai.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseUsage;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.model.anthropic.AnthropicChatResponseMetadata;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatResponseMetadata;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesChatResponseMetadata;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.List;

/**
 * {@link ProviderUsageNormalizer} 的七类语义、metadata 与 raw usage JSON 契约测试。
 *
 * <p>所有 SDK TokenUsage / metadata fixture 由 LangChain4j 1.16.2 公开 builder 直接构造， 不依赖反射或万能抽取器。
 */
class ProviderUsageNormalizerTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  // ---------- OpenAI Chat ----------

  /**
   * OpenAI Chat 把 cached/reasoning 从 input/output 中扣除并独立报告； providerTotal 直接采用 SDK
   * totalTokenCount，null ⇒ 0。
   */
  @Test
  void openAiChatSubtractsCachedAndReasoning() throws Exception {
    OpenAiTokenUsage usage =
        OpenAiTokenUsage.builder()
            .inputTokenCount(100)
            .outputTokenCount(50)
            .totalTokenCount(200)
            .inputTokensDetails(
                OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(30).build())
            .outputTokensDetails(
                OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(10).build())
            .build();
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .id("chatcmpl-req-1")
            .modelName("m")
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .serviceTier("priority")
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata);

    assertEquals(new ModelUsage(70, 40, 30, 0, 0, 10, 200), result.modelUsage());
    assertEquals("chatcmpl-req-1", result.requestId());
    assertEquals("priority", result.serviceTier());
    assertNull(rawUsageFields(result.rawUsageJson()).get("prompt"));
    assertNull(rawUsageFields(result.rawUsageJson()).get("choices"));
  }

  /** null total 在 OpenAI Chat 上必须规范化为 0，禁止使用 input+output 求和冒充 Provider 报告值。 */
  @Test
  void openAiChatReportsZeroProviderTotalWhenSdkOmitsIt() {
    OpenAiTokenUsage usage =
        OpenAiTokenUsage.builder().inputTokenCount(100).outputTokenCount(50).build();
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata);

    assertEquals(new ModelUsage(100, 50, 0, 0, 0, 0, 0), result.modelUsage());
  }

  /** cached 大于 input 时拒绝，避免账单被改成负数。 */
  @Test
  void openAiChatRejectsCachedExceedingInput() {
    OpenAiTokenUsage usage =
        OpenAiTokenUsage.builder()
            .inputTokenCount(10)
            .outputTokenCount(5)
            .inputTokensDetails(
                OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(20).build())
            .build();
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata));
  }

  /** reasoning 大于 output 时拒绝。 */
  @Test
  void openAiChatRejectsReasoningExceedingOutput() {
    OpenAiTokenUsage usage =
        OpenAiTokenUsage.builder()
            .inputTokenCount(10)
            .outputTokenCount(5)
            .outputTokensDetails(
                OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(20).build())
            .build();
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata));
  }

  /** serviceTier 在 OpenAI Chat typed metadata blank ⇒ null。 */
  @Test
  void openAiChatBlankServiceTierNormalizesToNull() {
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .tokenUsage(OpenAiTokenUsage.builder().build())
            .serviceTier("   ")
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata);

    assertNull(result.serviceTier());
  }

  /** requestId 在 metadata.id() blank 时规范化为 null。 */
  @Test
  void openAiChatBlankRequestIdNormalizesToNull() {
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .id("")
            .tokenUsage(OpenAiTokenUsage.builder().build())
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata);

    assertNull(result.requestId());
  }

  /** SSE 只有一个 usage 节点时，raw JSON 直接保存 object。 */
  @Test
  void openAiChatSingleUsageFromSseBecomesObject() throws Exception {
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .tokenUsage(
                OpenAiTokenUsage.builder().inputTokenCount(100).outputTokenCount(50).build())
            .finishReason(FinishReason.STOP)
            .rawServerSentEvents(
                List.of(
                    sse(
                        "{\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}"),
                    sse(
                        "{\"id\":\"x\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150}}"),
                    sse("[DONE]")))
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertTrue(parsed.isObject(), () -> "expected object, got " + result.rawUsageJson());
    assertEquals(100, parsed.path("prompt_tokens").asInt());
    assertEquals(50, parsed.path("completion_tokens").asInt());
    assertEquals(150, parsed.path("total_tokens").asInt());
  }

  /** SSE 出现多个 usage 节点时，raw JSON 保存为 array；非 usage 字段被排除。 */
  @Test
  void openAiChatMultipleUsageFromSseBecomesArray() throws Exception {
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .tokenUsage(OpenAiTokenUsage.builder().inputTokenCount(10).outputTokenCount(5).build())
            .finishReason(FinishReason.STOP)
            .rawServerSentEvents(
                List.of(
                    sse(
                        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}"),
                    sse("{\"type\":\"content_block_start\",\"content_block\":{\"text\":\"hi\"}}"),
                    sse("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}")))
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertTrue(parsed.isArray(), () -> "expected array, got " + result.rawUsageJson());
    assertEquals(2, parsed.size());
    assertEquals(10, parsed.get(0).path("input_tokens").asInt());
    assertEquals(5, parsed.get(1).path("output_tokens").asInt());
    // prompt/choices/content 等字段必须不出现在 rawUsageJson 中。
    assertNull(rawUsageFields(result.rawUsageJson()).get("prompt"));
    assertNull(rawUsageFields(result.rawUsageJson()).get("choices"));
    assertNull(rawUsageFields(result.rawUsageJson()).get("content"));
  }

  /** SSE 没有任何 usage 节点时回退 typed OpenAI Chat 字段 JSON，使用 Chat Completions API 字段名。 */
  @Test
  void openAiChatSseWithoutUsageFallsBackToTypedUsage() throws Exception {
    OpenAiTokenUsage usage =
        OpenAiTokenUsage.builder()
            .inputTokenCount(10)
            .outputTokenCount(5)
            .totalTokenCount(15)
            .build();
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .rawServerSentEvents(List.of(sse("{\"id\":\"x\",\"choices\":[]}")))
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertEquals(10, parsed.path("prompt_tokens").asInt());
    assertEquals(5, parsed.path("completion_tokens").asInt());
    assertEquals(15, parsed.path("total_tokens").asInt());
    assertTrue(parsed.path("inputTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("outputTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("totalTokenCount").isMissingNode(), () -> result.rawUsageJson());
  }

  /** OpenAI Chat 完全缺少 SSE metadata 时，必须回退到 typed Chat Completions API 字段 JSON。 */
  @Test
  void openAiChatWithoutRawSseFallsBackToTypedUsage() throws Exception {
    OpenAiTokenUsage usage =
        OpenAiTokenUsage.builder()
            .inputTokenCount(2)
            .outputTokenCount(3)
            .totalTokenCount(5)
            .build();
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertEquals(2, parsed.path("prompt_tokens").asInt());
    assertEquals(3, parsed.path("completion_tokens").asInt());
    assertEquals(5, parsed.path("total_tokens").asInt());
    assertTrue(parsed.path("inputTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("outputTokenCount").isMissingNode(), () -> result.rawUsageJson());
  }

  // ---------- OpenAI Responses ----------

  /** OpenAI Responses SDK 与 Chat 语义对齐：cached/reasoning 独立扣除。 */
  @Test
  void openAiResponsesSubtractsCachedAndReasoning() {
    OpenAiOfficialTokenUsage usage =
        OpenAiOfficialTokenUsage.builder()
            .inputTokenCount(200)
            .outputTokenCount(100)
            .totalTokenCount(400)
            .inputTokensDetails(
                OpenAiOfficialTokenUsage.InputTokensDetails.builder().cachedTokens(50).build())
            .outputTokensDetails(
                OpenAiOfficialTokenUsage.OutputTokensDetails.builder().reasoningTokens(20).build())
            .build();
    OpenAiOfficialResponsesChatResponseMetadata metadata =
        OpenAiOfficialResponsesChatResponseMetadata.builder()
            .id("resp-1")
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .serviceTier("default")
            .rawResponse(
                responseFixture(
                    "{\"input_tokens\":80,\"output_tokens\":120,\"total_tokens\":200}", null))
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI_RESPONSES, metadata);

    assertEquals(new ModelUsage(150, 80, 50, 0, 0, 20, 400), result.modelUsage());
    assertEquals("resp-1", result.requestId());
    assertEquals("default", result.serviceTier());
  }

  /** Responses null total ⇒ 0，行为与 Chat 一致。 */
  @Test
  void openAiResponsesReportsZeroProviderTotalWhenSdkOmitsIt() {
    OpenAiOfficialTokenUsage usage =
        OpenAiOfficialTokenUsage.builder().inputTokenCount(100).outputTokenCount(50).build();
    OpenAiOfficialResponsesChatResponseMetadata metadata =
        OpenAiOfficialResponsesChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI_RESPONSES, metadata);

    assertEquals(new ModelUsage(100, 50, 0, 0, 0, 0, 0), result.modelUsage());
  }

  /** Responses 序列化 rawResponse().usage()，保留 generated model additional properties。 */
  @Test
  void openAiResponsesRawUsagePreservesAdditionalProperties() throws Exception {
    OpenAiOfficialTokenUsage usage =
        OpenAiOfficialTokenUsage.builder().inputTokenCount(10).outputTokenCount(20).build();
    Response rawResponse =
        responseFixture(
            "{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30,\"model_extra\":\"generated\",\"model_count\":42}",
            null);
    OpenAiOfficialResponsesChatResponseMetadata metadata =
        OpenAiOfficialResponsesChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .rawResponse(rawResponse)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI_RESPONSES, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertEquals(10, parsed.path("input_tokens").asInt());
    assertEquals(20, parsed.path("output_tokens").asInt());
    assertEquals(30, parsed.path("total_tokens").asInt());
    assertEquals("generated", parsed.path("model_extra").asText());
    assertEquals(42, parsed.path("model_count").asInt());
    // 不应序列化整个 rawResponse（id/output 等字段不会泄露到 rawUsageJson）。
    assertTrue(parsed.path("id").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("output").isMissingNode(), () -> result.rawUsageJson());
  }

  /** Responses 在 rawResponse.usage 缺失时回退 typed OpenAiOfficialTokenUsage JSON。 */
  @Test
  void openAiResponsesFallsBackToTypedUsageWhenRawResponseHasNone() throws Exception {
    OpenAiOfficialTokenUsage usage =
        OpenAiOfficialTokenUsage.builder()
            .inputTokenCount(7)
            .outputTokenCount(11)
            .totalTokenCount(18)
            .build();
    OpenAiOfficialResponsesChatResponseMetadata metadata =
        OpenAiOfficialResponsesChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .rawResponse(responseFixture(null, null))
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI_RESPONSES, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertEquals(7, parsed.path("input_tokens").asInt());
    assertEquals(11, parsed.path("output_tokens").asInt());
    assertEquals(18, parsed.path("total_tokens").asInt());
    assertTrue(parsed.path("inputTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("outputTokenCount").isMissingNode(), () -> result.rawUsageJson());
  }

  // ---------- Google Gemini ----------

  /** Google 从 input/output 中扣除 cachedContentTokenCount 与 thoughtsTokenCount。 */
  @Test
  void googleSubtractsCachedAndThoughts() {
    GoogleAiGeminiTokenUsage usage =
        GoogleAiGeminiTokenUsage.builder()
            .inputTokenCount(120)
            .outputTokenCount(60)
            .cachedContentTokenCount(20)
            .thoughtsTokenCount(30)
            .totalTokenCount(300)
            .build();
    GoogleAiGeminiChatResponseMetadata metadata =
        GoogleAiGeminiChatResponseMetadata.builder()
            .id("gemini-1")
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.GOOGLE, metadata);

    // 七类语义：cached/thoughts 必须保留到 cacheRead/reasoning，后续 ModelCost 才能按两类独立计费。
    assertEquals(new ModelUsage(100, 30, 20, 0, 0, 30, 300), result.modelUsage());
    assertEquals("gemini-1", result.requestId());
    assertNull(result.serviceTier());
  }

  /** Google null total ⇒ 0。 */
  @Test
  void googleReportsZeroProviderTotalWhenSdkOmitsIt() {
    GoogleAiGeminiTokenUsage usage =
        GoogleAiGeminiTokenUsage.builder()
            .inputTokenCount(50)
            .outputTokenCount(40)
            .cachedContentTokenCount(10)
            .thoughtsTokenCount(5)
            .build();
    GoogleAiGeminiChatResponseMetadata metadata =
        GoogleAiGeminiChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.GOOGLE, metadata);

    // providerTotal=0，但 cached/thoughts 仍按七类语义保留。
    assertEquals(new ModelUsage(40, 35, 10, 0, 0, 5, 0), result.modelUsage());
  }

  /** Google 没有 raw transport 时使用 Provider 原字段名生成 typed usage JSON。 */
  @Test
  void googleRawUsageUsesProviderFieldNames() throws Exception {
    GoogleAiGeminiTokenUsage usage =
        GoogleAiGeminiTokenUsage.builder()
            .inputTokenCount(11)
            .outputTokenCount(13)
            .cachedContentTokenCount(2)
            .thoughtsTokenCount(3)
            .totalTokenCount(17)
            .build();
    GoogleAiGeminiChatResponseMetadata metadata =
        GoogleAiGeminiChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.GOOGLE, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertEquals(11, parsed.path("promptTokenCount").asInt());
    assertEquals(13, parsed.path("candidatesTokenCount").asInt());
    assertEquals(2, parsed.path("cachedContentTokenCount").asInt());
    assertEquals(3, parsed.path("thoughtsTokenCount").asInt());
    assertEquals(17, parsed.path("totalTokenCount").asInt());
    // SDK 字段名（inputTokenCount/outputTokenCount）不应出现在 Google rawUsageJson。
    assertTrue(parsed.path("inputTokenCount").isMissingNode());
    assertTrue(parsed.path("outputTokenCount").isMissingNode());
  }

  /** Google metadata 或 usage 为 null 时 rawUsageJson 规范化为 "{}"。 */
  @Test
  void googleEmptyUsageSerializesAsEmptyObject() {
    ProviderUsageNormalizer.NormalizedUsage none =
        ProviderUsageNormalizer.normalize(ProviderType.GOOGLE, null);
    assertEquals("{}", none.rawUsageJson());
    assertEquals(new ModelUsage(0, 0, 0, 0, 0, 0, 0), none.modelUsage());

    GoogleAiGeminiChatResponseMetadata emptyMetadata =
        GoogleAiGeminiChatResponseMetadata.builder().finishReason(FinishReason.STOP).build();
    ProviderUsageNormalizer.NormalizedUsage emptyUsage =
        ProviderUsageNormalizer.normalize(ProviderType.GOOGLE, emptyMetadata);
    assertEquals("{}", emptyUsage.rawUsageJson());
  }

  // ---------- Anthropic ----------

  /** Anthropic input/output 原样，cacheCreation ⇒ cacheWrite，cacheRead 独立，total=0。 */
  @Test
  void anthropicKeepsInputOutputAndForcesZeroProviderTotal() {
    AnthropicTokenUsage usage =
        AnthropicTokenUsage.builder()
            .inputTokenCount(100)
            .outputTokenCount(50)
            .cacheCreationInputTokens(20)
            .cacheReadInputTokens(10)
            .build();
    AnthropicChatResponseMetadata metadata =
        AnthropicChatResponseMetadata.builder()
            .id("anthropic-1")
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.ANTHROPIC, metadata);

    assertEquals(new ModelUsage(100, 50, 10, 20, 0, 0, 0), result.modelUsage());
    assertEquals("anthropic-1", result.requestId());
    assertNull(result.serviceTier());
  }

  /** Anthropic reasoning 与 cacheWriteLong 必须为 0；不向 input/output 求和。 */
  @Test
  void anthropicIgnoresReasoningAndDoesNotSumInputOutput() {
    AnthropicTokenUsage usage =
        AnthropicTokenUsage.builder()
            .inputTokenCount(100)
            .outputTokenCount(50)
            .cacheCreationInputTokens(5)
            .cacheReadInputTokens(5)
            .build();
    AnthropicChatResponseMetadata metadata =
        AnthropicChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.ANTHROPIC, metadata);

    assertEquals(0, result.modelUsage().reasoningTokens());
    assertEquals(0, result.modelUsage().cacheWriteLongTokens());
    // 严格禁止 input+output 派生 total=150；必须是 0。
    assertEquals(0, result.modelUsage().providerTotalTokens());
  }

  /** Anthropic SDK 负值（cacheRead）触发 IAE，由 LangChainModelProvider 转 invalid SDK response。 */
  @Test
  void anthropicRejectsNegativeSdkValue() {
    AnthropicTokenUsage usage =
        AnthropicTokenUsage.builder()
            .inputTokenCount(10)
            .outputTokenCount(5)
            .cacheReadInputTokens(-1)
            .build();
    AnthropicChatResponseMetadata metadata =
        AnthropicChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderUsageNormalizer.normalize(ProviderType.ANTHROPIC, metadata));
  }

  /** Anthropic SSE 出现多个 usage 时保存为 array，并排除文本/工具字段。 */
  @Test
  void anthropicMultipleUsageFromSseBecomesArray() throws Exception {
    AnthropicTokenUsage usage =
        AnthropicTokenUsage.builder().inputTokenCount(10).outputTokenCount(5).build();
    AnthropicChatResponseMetadata metadata =
        AnthropicChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .rawServerSentEvents(
                List.of(
                    sse(
                        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"cache_creation_input_tokens\":2}}}"),
                    sse("{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hi\"}}"),
                    sse("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}"),
                    sse("{\"type\":\"message_stop\"}")))
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.ANTHROPIC, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertTrue(parsed.isArray(), () -> "expected array, got " + result.rawUsageJson());
    assertEquals(2, parsed.size());
    assertEquals(10, parsed.get(0).path("input_tokens").asInt());
    assertEquals(5, parsed.get(1).path("output_tokens").asInt());
    assertNull(rawUsageFields(result.rawUsageJson()).get("text"));
    assertNull(rawUsageFields(result.rawUsageJson()).get("content_block"));
  }

  // ---------- Unknown / generic TokenUsage ----------

  /** 未知 TokenUsage 类型走通用 fallback：只取 input/output/total，其他类别 0。 */
  @Test
  void unknownTokenUsagePassesThroughWithZeroTotalWhenNull() {
    TokenUsage usage = new TokenUsage(100, 50, null);
    var metadata =
        ChatResponseMetadata.builder()
            .id("plain-1")
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata);

    assertEquals(new ModelUsage(100, 50, 0, 0, 0, 0, 0), result.modelUsage());
    assertEquals("plain-1", result.requestId());
    assertNull(result.serviceTier());
  }

  /** 未知 TokenUsage 带 total 时直接采用，禁止使用六类求和。 */
  @Test
  void unknownTokenUsageProviderTotalTakesSdkValue() {
    TokenUsage usage = new TokenUsage(100, 50, 175);
    var metadata =
        ChatResponseMetadata.builder().tokenUsage(usage).finishReason(FinishReason.STOP).build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata);

    assertEquals(175, result.modelUsage().providerTotalTokens());
  }

  // ---------- Common ----------

  /** typed provider 字段 JSON 在 Anthropic fallback 时使用 Anthropic API 字段名；绝不伪造 total。 */
  @Test
  void anthropicTypedUsageJsonUsesAnthropicFieldNames() throws Exception {
    AnthropicTokenUsage usage =
        AnthropicTokenUsage.builder()
            .inputTokenCount(10)
            .outputTokenCount(5)
            .cacheCreationInputTokens(3)
            .cacheReadInputTokens(2)
            .build();
    AnthropicChatResponseMetadata metadata =
        AnthropicChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.ANTHROPIC, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertEquals(10, parsed.path("input_tokens").asInt());
    assertEquals(5, parsed.path("output_tokens").asInt());
    assertEquals(3, parsed.path("cache_creation_input_tokens").asInt());
    assertEquals(2, parsed.path("cache_read_input_tokens").asInt());
    // SDK 内部 camelCase 字段名不应泄露到 fallback JSON。
    assertTrue(parsed.path("inputTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("outputTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(
        parsed.path("cacheCreationInputTokens").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("cacheReadInputTokens").isMissingNode(), () -> result.rawUsageJson());
    // Anthropic API 无原生 total，禁止伪造。
    assertTrue(parsed.path("total_tokens").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("totalTokenCount").isMissingNode(), () -> result.rawUsageJson());
  }

  /** typed provider 字段 JSON 在 OpenAI Chat fallback 时使用 Chat Completions API 字段名。 */
  @Test
  void openAiChatTypedUsageJsonUsesOpenAiFieldNames() throws Exception {
    OpenAiTokenUsage usage =
        OpenAiTokenUsage.builder()
            .inputTokenCount(10)
            .outputTokenCount(5)
            .totalTokenCount(15)
            .inputTokensDetails(
                OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(2).build())
            .outputTokensDetails(
                OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(1).build())
            .build();
    OpenAiChatResponseMetadata metadata =
        OpenAiChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertEquals(10, parsed.path("prompt_tokens").asInt());
    assertEquals(5, parsed.path("completion_tokens").asInt());
    assertEquals(15, parsed.path("total_tokens").asInt());
    assertEquals(2, parsed.path("prompt_tokens_details").path("cached_tokens").asInt());
    assertEquals(1, parsed.path("completion_tokens_details").path("reasoning_tokens").asInt());
    // SDK 内部 camelCase 字段名不应出现。
    assertTrue(parsed.path("inputTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("outputTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("totalTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("inputTokensDetails").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("outputTokensDetails").isMissingNode(), () -> result.rawUsageJson());
    // Responses API 字段名不属于 OpenAI Chat fallback。
    assertTrue(parsed.path("input_tokens").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("output_tokens").isMissingNode(), () -> result.rawUsageJson());
  }

  /** typed provider 字段 JSON 在 OpenAI Responses fallback 时使用 Responses API 字段名与嵌套 details。 */
  @Test
  void openAiResponsesTypedUsageJsonIncludesNestedDetails() throws Exception {
    OpenAiOfficialTokenUsage usage =
        OpenAiOfficialTokenUsage.builder()
            .inputTokenCount(20)
            .outputTokenCount(40)
            .totalTokenCount(60)
            .inputTokensDetails(
                OpenAiOfficialTokenUsage.InputTokensDetails.builder().cachedTokens(5).build())
            .outputTokensDetails(
                OpenAiOfficialTokenUsage.OutputTokensDetails.builder().reasoningTokens(7).build())
            .build();
    OpenAiOfficialResponsesChatResponseMetadata metadata =
        OpenAiOfficialResponsesChatResponseMetadata.builder()
            .tokenUsage(usage)
            .finishReason(FinishReason.STOP)
            .build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI_RESPONSES, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertEquals(20, parsed.path("input_tokens").asInt());
    assertEquals(40, parsed.path("output_tokens").asInt());
    assertEquals(60, parsed.path("total_tokens").asInt());
    assertEquals(5, parsed.path("input_tokens_details").path("cached_tokens").asInt());
    assertEquals(7, parsed.path("output_tokens_details").path("reasoning_tokens").asInt());
    // SDK 内部 camelCase 字段名不应出现。
    assertTrue(parsed.path("inputTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("outputTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("totalTokenCount").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("inputTokensDetails").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("outputTokensDetails").isMissingNode(), () -> result.rawUsageJson());
    // Chat Completions 字段名不属于 Responses fallback。
    assertTrue(parsed.path("prompt_tokens").isMissingNode(), () -> result.rawUsageJson());
    assertTrue(parsed.path("completion_tokens").isMissingNode(), () -> result.rawUsageJson());
  }

  /** typed provider 字段 JSON 在通用 TokenUsage fallback 时使用通用字段名。 */
  @Test
  void genericTokenUsageTypedJsonUsesCommonFieldNames() throws Exception {
    TokenUsage usage = new TokenUsage(7, 11, 18);
    var metadata =
        ChatResponseMetadata.builder().tokenUsage(usage).finishReason(FinishReason.STOP).build();

    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.GOOGLE, metadata);

    JsonNode parsed = OBJECT_MAPPER.readTree(result.rawUsageJson());
    assertEquals(7, parsed.path("inputTokenCount").asInt());
    assertEquals(11, parsed.path("outputTokenCount").asInt());
    assertEquals(18, parsed.path("totalTokenCount").asInt());
    // 通用 fallback 不应输出 Provider 特定的 cachedContentTokenCount / thoughtsTokenCount。
    assertTrue(parsed.path("cachedContentTokenCount").isMissingNode());
    assertTrue(parsed.path("thoughtsTokenCount").isMissingNode());
  }

  /** metadata 完全为 null 时 result 字段全部合理 fallback。 */
  @Test
  void nullMetadataProducesEmptyResult() {
    ProviderUsageNormalizer.NormalizedUsage result =
        ProviderUsageNormalizer.normalize(ProviderType.OPENAI, null);

    assertEquals(new ModelUsage(0, 0, 0, 0, 0, 0, 0), result.modelUsage());
    assertNull(result.requestId());
    assertNull(result.serviceTier());
    assertEquals("{}", result.rawUsageJson());
  }

  /** null providerType 触发 IAE，避免悄悄走错分支。 */
  @Test
  void rejectsNullProviderType() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProviderUsageNormalizer.normalize(
                null,
                ChatResponseMetadata.builder()
                    .tokenUsage(new TokenUsage(null, null, null))
                    .build()));
  }

  // ---------- helpers ----------

  private static ServerSentEvent sse(String data) {
    return new ServerSentEvent("message", data);
  }

  private static ResponseUsage responseUsageFixture(long input, long output, long total) {
    try {
      String json =
          String.format(
              "{\"input_tokens\":%d,\"output_tokens\":%d,\"total_tokens\":%d}",
              input, output, total);
      return ObjectMappers.jsonMapper().readValue(json, ResponseUsage.class);
    } catch (Exception error) {
      throw new AssertionError("cannot build ResponseUsage fixture", error);
    }
  }

  private static Response responseFixture(String usageJson, String extraPropertiesJson) {
    try {
      StringBuilder sb = new StringBuilder("{\"id\":\"resp\",\"created_at\":1.0");
      if (usageJson != null) {
        sb.append(",\"usage\":").append(usageJson);
      }
      if (extraPropertiesJson != null) {
        sb.append(",").append(extraPropertiesJson);
      }
      sb.append("}");
      return ObjectMappers.jsonMapper().readValue(sb.toString(), Response.class);
    } catch (Exception error) {
      throw new AssertionError("cannot build Response fixture", error);
    }
  }

  private static JsonNode rawUsageFields(String json) {
    try {
      return OBJECT_MAPPER.readTree(json);
    } catch (Exception error) {
      throw new AssertionError("rawUsageJson is not valid JSON: " + json, error);
    }
  }
}

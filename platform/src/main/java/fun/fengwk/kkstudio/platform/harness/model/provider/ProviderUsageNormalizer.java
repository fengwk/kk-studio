package fun.fengwk.kkstudio.platform.harness.model.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.model.anthropic.AnthropicChatResponseMetadata;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiResponsesChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 四类 Provider 的 usage、metadata 与 raw usage JSON 归一化器。
 *
 * <p>{@link LangChainModelProvider#toResponse} 在 SDK 流完成后调用本类，把 SDK 类型化的 {@link TokenUsage} 与
 * {@link ChatResponseMetadata} 转换为 harness 内部契约的 {@link ModelUsage}、{@code requestId}、{@code
 * serviceTier} 与 {@code rawUsageJson}。Adapter 在构造时把自身 {@link ProviderType} 注入基类， 归一化过程不再依赖运行时猜类型。
 *
 * <h2>七类语义</h2>
 *
 * <ul>
 *   <li>OpenAI Chat / OpenAI Responses：{@code input -= cached}, {@code output -= reasoning}；{@code
 *       cacheRead} 与 {@code reasoning} 独立报告，{@code cacheWrite=0}；{@code providerTotalTokens} 只取 SDK
 *       total，null ⇒ 0， 禁止使用前六类求和。
 *   <li>Google Gemini：{@code input -= cachedContentTokenCount}，{@code output -=
 *       thoughtsTokenCount}；{@code providerTotalTokens} 取 SDK total，null ⇒ 0。
 *   <li>Anthropic：{@code input}、{@code output} 原样；{@code cacheCreation ⇒ cacheWrite}，{@code
 *       cacheRead} 独立；{@code reasoning=0}，{@code cacheWriteLong=0}；Anthropic API 无原生 total，{@code
 *       providerTotalTokens=0}，禁止用 input+output 冒充 Provider 报告值。
 *   <li>未知/普通 {@link TokenUsage}：{@code input}、{@code output} 原样；{@code providerTotalTokens} 仅取非
 *       null SDK total，null ⇒ 0；六类其他类别一律 0。
 * </ul>
 *
 * <p>所有 token 必须非负；{@code cache > input}、{@code reasoning > output}、SDK 负值都会抛 {@link
 * IllegalArgumentException}，由 {@link LangChainModelProvider} 的 {@code "invalid SDK response"} 路径返回。
 *
 * <h2>metadata</h2>
 *
 * <p>{@code requestId} 取 {@link ChatResponseMetadata#id()}，blank 规范化为 {@code null}。{@code
 * serviceTier} 仅从 OpenAI Chat / OpenAI Responses typed metadata 读取，blank ⇒ {@code null}；其他 Provider
 * 一律 {@code null}。
 *
 * <h2>rawUsageJson</h2>
 *
 * <p>只承载 usage 元数据，禁止保存 prompt 或响应正文：
 *
 * <ul>
 *   <li>OpenAI Chat / OpenAI Responses / Anthropic：从 typed metadata {@code
 *       rawServerSentEvents().data()} JSON 中递归提取所有名为 {@code usage} 的 object/array 节点（包括 final
 *       {@code response.completed} 事件）；0 个时回退 typed provider 字段 JSON； 1 个直接 object/array；多个保存为
 *       array；忽略 {@code [DONE]} 和非 JSON 行。保留 unknown usage properties，禁止序列化整个 response payload。
 *   <li>Google / 无 raw transport：生成 Provider 原字段名的 typed usage object （{@code
 *       promptTokenCount/candidatesTokenCount/cachedContentTokenCount/
 *       thoughtsTokenCount/totalTokenCount}）；null metadata/usage ⇒ {@code "{}"}。
 * </ul>
 *
 * <p>所有结果必须合法 JSON object 或 array。
 */
final class ProviderUsageNormalizer {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ProviderUsageNormalizer() {}

  /**
   * 把 SDK 完成响应的 metadata 归一化为 harness 内部契约。
   *
   * @param providerType 由 Adapter 在构造时绑定的 {@link ProviderType}，决定 七类语义分支与 raw JSON 提取策略；不允许根据
   *     metadata 重新猜类型
   * @param metadata SDK 返回的 metadata，允许为 {@code null}
   */
  static NormalizedUsage normalize(ProviderType providerType, ChatResponseMetadata metadata) {
    if (providerType == null) {
      throw new IllegalArgumentException("providerType must not be null");
    }
    TokenUsage usage = metadata == null ? null : metadata.tokenUsage();
    String requestId = requestId(metadata);
    String serviceTier = serviceTier(providerType, metadata);
    String rawUsageJson = rawUsageJson(providerType, metadata, usage);
    ModelUsage modelUsage = modelUsage(providerType, usage);
    return new NormalizedUsage(modelUsage, requestId, serviceTier, rawUsageJson);
  }

  private static String requestId(ChatResponseMetadata metadata) {
    if (metadata == null) {
      return null;
    }
    String id = metadata.id();
    return id == null || id.isBlank() ? null : id;
  }

  private static String serviceTier(ProviderType providerType, ChatResponseMetadata metadata) {
    if (metadata == null) {
      return null;
    }
    String tier = null;
    if (providerType == ProviderType.OPENAI && metadata instanceof OpenAiChatResponseMetadata oa) {
      tier = oa.serviceTier();
    } else if (providerType == ProviderType.OPENAI_RESPONSES
        && metadata instanceof OpenAiResponsesChatResponseMetadata oa) {
      tier = oa.serviceTier();
    }
    return tier == null || tier.isBlank() ? null : tier;
  }

  private static ModelUsage modelUsage(ProviderType providerType, TokenUsage usage) {
    if (usage == null) {
      return new ModelUsage(0, 0, 0, 0, 0, 0, 0);
    }
    return switch (providerType) {
      case OPENAI, OPENAI_RESPONSES -> usage instanceof OpenAiTokenUsage openAi
          ? openAiUsage(openAi)
          : genericUsage(usage);
      case ANTHROPIC -> usage instanceof AnthropicTokenUsage anthropic
          ? anthropicUsage(anthropic)
          : genericUsage(usage);
      case GOOGLE -> usage instanceof GoogleAiGeminiTokenUsage google
          ? googleUsage(google)
          : genericUsage(usage);
    };
  }

  private static ModelUsage genericUsage(TokenUsage usage) {
    long input = valueOrZero(usage.inputTokenCount());
    long output = valueOrZero(usage.outputTokenCount());
    long providerTotal = valueOrZero(usage.totalTokenCount());
    rejectNegative(input, "input tokens");
    rejectNegative(output, "output tokens");
    rejectNegative(providerTotal, "provider total tokens");
    return new ModelUsage(input, output, 0, 0, 0, 0, providerTotal);
  }

  private static ModelUsage openAiUsage(OpenAiTokenUsage usage) {
    long input = valueOrZero(usage.inputTokenCount());
    long output = valueOrZero(usage.outputTokenCount());
    long cacheRead =
        usage.inputTokensDetails() == null
            ? 0
            : valueOrZero(usage.inputTokensDetails().cachedTokens());
    long reasoning =
        usage.outputTokensDetails() == null
            ? 0
            : valueOrZero(usage.outputTokensDetails().reasoningTokens());
    long providerTotal = valueOrZero(usage.totalTokenCount());
    return openAiDerivedUsage(input, output, cacheRead, reasoning, providerTotal);
  }

  private static ModelUsage openAiDerivedUsage(
      long input, long output, long cacheRead, long reasoning, long providerTotal) {
    rejectNegative(input, "input tokens");
    rejectNegative(output, "output tokens");
    rejectNegative(cacheRead, "cached input tokens");
    rejectNegative(reasoning, "reasoning output tokens");
    rejectNegative(providerTotal, "provider total tokens");
    long billedInput = subtractCategory(input, cacheRead, "cached input tokens");
    long billedOutput = subtractCategory(output, reasoning, "reasoning output tokens");
    return new ModelUsage(billedInput, billedOutput, cacheRead, 0, 0, reasoning, providerTotal);
  }

  private static ModelUsage anthropicUsage(AnthropicTokenUsage usage) {
    long input = valueOrZero(usage.inputTokenCount());
    long output = valueOrZero(usage.outputTokenCount());
    long cacheWrite = valueOrZero(usage.cacheCreationInputTokens());
    long cacheRead = valueOrZero(usage.cacheReadInputTokens());
    rejectNegative(input, "input tokens");
    rejectNegative(output, "output tokens");
    rejectNegative(cacheWrite, "cache creation tokens");
    rejectNegative(cacheRead, "cache read tokens");
    // Anthropic API 没有原生 total：禁用 input+output 派生，固定 0。
    return new ModelUsage(input, output, cacheRead, cacheWrite, 0, 0, 0);
  }

  private static ModelUsage googleUsage(GoogleAiGeminiTokenUsage usage) {
    long input = valueOrZero(usage.inputTokenCount());
    long output = valueOrZero(usage.outputTokenCount());
    long cached = valueOrZero(usage.cachedContentTokenCount());
    long thoughts = valueOrZero(usage.thoughtsTokenCount());
    long providerTotal = valueOrZero(usage.totalTokenCount());
    rejectNegative(input, "input tokens");
    rejectNegative(output, "output tokens");
    rejectNegative(cached, "cached content tokens");
    rejectNegative(thoughts, "thoughts tokens");
    rejectNegative(providerTotal, "provider total tokens");
    long billedInput = subtractCategory(input, cached, "cached content tokens");
    long billedOutput = subtractCategory(output, thoughts, "thoughts tokens");
    // cached/thoughts 是 Provider 单独计费的类别，必须保留到 ModelUsage 的 cacheRead / reasoning，
    // 不然后续 ModelCost 无法按这两类计费。
    return new ModelUsage(billedInput, billedOutput, cached, 0, 0, thoughts, providerTotal);
  }

  private static long valueOrZero(Integer value) {
    if (value == null) {
      return 0;
    }
    rejectNegative(value.longValue(), "SDK token value");
    return value.longValue();
  }

  private static void rejectNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }

  private static long subtractCategory(long total, long category, String categoryName) {
    if (category > total) {
      throw new IllegalArgumentException(categoryName + " must not exceed its reported total");
    }
    return total - category;
  }

  private static String rawUsageJson(
      ProviderType providerType, ChatResponseMetadata metadata, TokenUsage usage) {
    return switch (providerType) {
      case OPENAI -> openAiChatRawUsageJson(metadata, usage);
      case OPENAI_RESPONSES -> openAiResponsesRawUsageJson(metadata, usage);
      case ANTHROPIC -> anthropicRawUsageJson(metadata, usage);
      case GOOGLE -> googleRawUsageJson(usage);
    };
  }

  private static String openAiChatRawUsageJson(ChatResponseMetadata metadata, TokenUsage usage) {
    List<JsonNode> usages = collectUsageNodes(sseDataOf(metadata));
    if (usages.isEmpty()) {
      return typedProviderUsageJson(ProviderType.OPENAI, usage);
    }
    if (usages.size() == 1) {
      return writeJsonString(usages.get(0));
    }
    ArrayNode array = OBJECT_MAPPER.createArrayNode();
    for (JsonNode node : usages) {
      array.add(node);
    }
    return writeJsonString(array);
  }

  private static String openAiResponsesRawUsageJson(
      ChatResponseMetadata metadata, TokenUsage usage) {
    List<JsonNode> usages = collectUsageNodes(sseDataOf(metadata));
    if (usages.isEmpty()) {
      return typedProviderUsageJson(ProviderType.OPENAI_RESPONSES, usage);
    }
    if (usages.size() == 1) {
      return writeJsonString(usages.get(0));
    }
    ArrayNode array = OBJECT_MAPPER.createArrayNode();
    for (JsonNode node : usages) {
      array.add(node);
    }
    return writeJsonString(array);
  }

  private static String anthropicRawUsageJson(ChatResponseMetadata metadata, TokenUsage usage) {
    List<JsonNode> usages = collectUsageNodes(sseDataOf(metadata));
    if (usages.isEmpty()) {
      return typedProviderUsageJson(ProviderType.ANTHROPIC, usage);
    }
    if (usages.size() == 1) {
      return writeJsonString(usages.get(0));
    }
    ArrayNode array = OBJECT_MAPPER.createArrayNode();
    for (JsonNode node : usages) {
      array.add(node);
    }
    return writeJsonString(array);
  }

  private static String googleRawUsageJson(TokenUsage usage) {
    return typedProviderUsageJson(ProviderType.GOOGLE, usage);
  }

  private static List<JsonNode> collectUsageNodes(List<String> dataLines) {
    List<JsonNode> result = new ArrayList<>();
    for (String data : dataLines) {
      if (data == null) {
        continue;
      }
      String trimmed = data.trim();
      if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
        continue;
      }
      JsonNode node;
      try {
        node = OBJECT_MAPPER.readTree(trimmed);
      } catch (JsonProcessingException ignored) {
        continue;
      }
      collectUsageNodesInto(node, result);
    }
    return result;
  }

  private static List<String> sseDataOf(ChatResponseMetadata metadata) {
    if (metadata == null) {
      return List.of();
    }
    List<ServerSentEvent> events = null;
    if (metadata instanceof OpenAiChatResponseMetadata oa) {
      events = oa.rawServerSentEvents();
    } else if (metadata instanceof OpenAiResponsesChatResponseMetadata oa) {
      events = oa.rawServerSentEvents();
    } else if (metadata instanceof AnthropicChatResponseMetadata anth) {
      events = anth.rawServerSentEvents();
    }
    if (events == null || events.isEmpty()) {
      return List.of();
    }
    List<String> data = new ArrayList<>(events.size());
    for (ServerSentEvent event : events) {
      data.add(event.data());
    }
    return data;
  }

  private static void collectUsageNodesInto(JsonNode node, List<JsonNode> sink) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      JsonNode usage = node.get("usage");
      if (usage != null && (usage.isObject() || usage.isArray())) {
        sink.add(usage);
      }
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        collectUsageNodesInto(field.getValue(), sink);
      }
    } else if (node.isArray()) {
      for (JsonNode element : node) {
        collectUsageNodesInto(element, sink);
      }
    }
  }

  private static String typedProviderUsageJson(ProviderType providerType, TokenUsage usage) {
    if (usage == null) {
      return "{}";
    }
    // KISS：直接对每个 SDK typed usage 按 Provider API 的 JSON 字段名展开，禁止反射或万能抽取器。
    return switch (providerType) {
      case OPENAI -> usage instanceof OpenAiTokenUsage openAi
          ? openAiChatTypedUsageJson(openAi)
          : genericTypedUsageJson(usage);
      case OPENAI_RESPONSES -> usage instanceof OpenAiTokenUsage openAi
          ? openAiResponsesTypedUsageJson(openAi)
          : genericTypedUsageJson(usage);
      case ANTHROPIC -> usage instanceof AnthropicTokenUsage anthropic
          ? anthropicTypedUsageJson(anthropic)
          : genericTypedUsageJson(usage);
      case GOOGLE -> usage instanceof GoogleAiGeminiTokenUsage google
          ? googleTypedUsageJson(google)
          : genericTypedUsageJson(usage);
    };
  }

  private static String genericTypedUsageJson(TokenUsage usage) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    putIfPresent(node, "inputTokenCount", usage.inputTokenCount());
    putIfPresent(node, "outputTokenCount", usage.outputTokenCount());
    putIfPresent(node, "totalTokenCount", usage.totalTokenCount());
    return writeJsonString(node);
  }

  /**
   * OpenAI Chat Completions Provider API JSON 字段名：{@code prompt_tokens} / {@code
   * completion_tokens}。
   */
  private static String openAiChatTypedUsageJson(OpenAiTokenUsage openAi) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    putIfPresent(node, "prompt_tokens", openAi.inputTokenCount());
    putIfPresent(node, "completion_tokens", openAi.outputTokenCount());
    putIfPresent(node, "total_tokens", openAi.totalTokenCount());
    if (openAi.inputTokensDetails() != null) {
      ObjectNode details = OBJECT_MAPPER.createObjectNode();
      putIfPresent(details, "cached_tokens", openAi.inputTokensDetails().cachedTokens());
      if (details.size() > 0) {
        node.set("prompt_tokens_details", details);
      }
    }
    if (openAi.outputTokensDetails() != null) {
      ObjectNode details = OBJECT_MAPPER.createObjectNode();
      putIfPresent(details, "reasoning_tokens", openAi.outputTokensDetails().reasoningTokens());
      if (details.size() > 0) {
        node.set("completion_tokens_details", details);
      }
    }
    return writeJsonString(node);
  }

  /** OpenAI Responses API JSON 字段名：{@code input_tokens} / {@code output_tokens}。 */
  private static String openAiResponsesTypedUsageJson(OpenAiTokenUsage openAi) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    putIfPresent(node, "input_tokens", openAi.inputTokenCount());
    putIfPresent(node, "output_tokens", openAi.outputTokenCount());
    putIfPresent(node, "total_tokens", openAi.totalTokenCount());
    if (openAi.inputTokensDetails() != null) {
      ObjectNode details = OBJECT_MAPPER.createObjectNode();
      putIfPresent(details, "cached_tokens", openAi.inputTokensDetails().cachedTokens());
      if (details.size() > 0) {
        node.set("input_tokens_details", details);
      }
    }
    if (openAi.outputTokensDetails() != null) {
      ObjectNode details = OBJECT_MAPPER.createObjectNode();
      putIfPresent(details, "reasoning_tokens", openAi.outputTokensDetails().reasoningTokens());
      if (details.size() > 0) {
        node.set("output_tokens_details", details);
      }
    }
    return writeJsonString(node);
  }

  /** Anthropic API JSON 字段名：{@code input_tokens} / {@code output_tokens} 等；不伪造 total。 */
  private static String anthropicTypedUsageJson(AnthropicTokenUsage anthropic) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    putIfPresent(node, "input_tokens", anthropic.inputTokenCount());
    putIfPresent(node, "output_tokens", anthropic.outputTokenCount());
    putIfPresent(node, "cache_creation_input_tokens", anthropic.cacheCreationInputTokens());
    putIfPresent(node, "cache_read_input_tokens", anthropic.cacheReadInputTokens());
    return writeJsonString(node);
  }

  /** Google Gemini API JSON 字段名：{@code promptTokenCount} / {@code candidatesTokenCount} 等。 */
  private static String googleTypedUsageJson(GoogleAiGeminiTokenUsage google) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    putIfPresent(node, "promptTokenCount", google.inputTokenCount());
    putIfPresent(node, "candidatesTokenCount", google.outputTokenCount());
    putIfPresent(node, "cachedContentTokenCount", google.cachedContentTokenCount());
    putIfPresent(node, "thoughtsTokenCount", google.thoughtsTokenCount());
    putIfPresent(node, "totalTokenCount", google.totalTokenCount());
    return writeJsonString(node);
  }

  private static void putIfPresent(ObjectNode node, String name, Integer value) {
    if (value != null) {
      node.put(name, value.longValue());
    }
  }

  private static String writeJsonString(JsonNode node) {
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot serialize usage JSON", error);
    }
  }

  /**
   * 归一化结果。
   *
   * @param modelUsage 七类 token 归一化结果
   * @param requestId Provider 报告的请求 id，blank 规范化为 {@code null}
   * @param serviceTier OpenAI Chat/Responses 报告的 service tier，其他 Provider 一律 {@code null}
   * @param rawUsageJson 仅承载 usage 元数据的合法 JSON object 或 array
   */
  record NormalizedUsage(
      ModelUsage modelUsage, String requestId, String serviceTier, String rawUsageJson) {

    NormalizedUsage {
      modelUsage = Objects.requireNonNull(modelUsage, "modelUsage");
      rawUsageJson = Objects.requireNonNull(rawUsageJson, "rawUsageJson");
    }
  }
}

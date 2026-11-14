package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.impl.RequestBodySizeGuard;

import java.util.List;
import java.util.Objects;

/**
 * Anthropic Messages 协议的请求编码器。
 *
 * <p>负责将运行时 {@link ProviderRequest} 转换为符合 Anthropic 规范的 UTF-8 请求 JSON 字节数组。
 *
 * <p>请求体以 variant 的 native {@code protocolOptions} 为合并起点：运行时所有权字段 （{@code model}/{@code
 * max_tokens}/{@code stream}/{@code messages}/{@code system}/{@code cache_control}） 冲突显式失败，{@code
 * tools} 保留 native 条目后追加 runtime 工具，其余官方顶层字段无损保留；cache marker 与请求体大小均基于最终合并结果。
 *
 * <p>{@code cacheControl} 只表达留存档位：{@code retention != NONE} 时在 tools/system/历史边界写入固定的原生 {@code
 * cache_control} ephemeral 标记，{@code LONG} 额外携带 {@code ttl:"1h"}；会话 {@code key} 不属于 Anthropic
 * 原生选项，因此不下发。
 *
 * <p>assistant replay 与 system/时间前缀无关，只与 replay state 的 affinity（Provider type/name/wire
 * model）绑定。affinity 不匹配时按 durable 语义回退投影，绝不因为存在 replay 而拒绝；affinity 匹配时先校验真实 payload 形状与 durable
 * 一致性，再原位回放全部原生事实（含 {@code thinking} 的 signature、{@code redacted_thinking}、未知 block 与官方附加字段）。
 * payload 以 {@code compaction} block 开头时代表历史已被摘要，校验通过后清空已编码消息使该消息成为首条消息。
 */
public final class AnthropicRequestEncoder {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;

  /** 语义比对专用读取器：数值按 scale 无关归一，只用于 replay 与 durable 的一致性比对，不参与 wire 输出。 */
  private static final ObjectReader COMPARISON_READER =
      OBJECT_MAPPER.reader().with(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /**
   * 运行时独占的顶层请求事实：{@code cache_control} 表示 prompt cache 的运行时所有权（标记由本编码器写入
   * system/tools/messages），native options 一律不得声明，冲突显式失败。
   */
  private static final List<String> RUNTIME_OWNED_OPTION_FIELDS =
      List.of("model", "max_tokens", "stream", "messages", "system", "cache_control");

  private static final String OUTPUT_CONFIG_BETA = "mid-conversation-output-config-2026-07-01";
  private static final String THINKING_BINDING_BETA = "thinking-binding-controls-2026-08-01";
  private static final String INLINE_TOOLS_BETA = "inline-tools-2026-09-15";

  private final RequestBodySizeGuard bodySizeGuard;

  public AnthropicRequestEncoder() {
    this(RequestBodySizeGuard.DEFAULT);
  }

  AnthropicRequestEncoder(RequestBodySizeGuard bodySizeGuard) {
    this.bodySizeGuard = Objects.requireNonNull(bodySizeGuard, "bodySizeGuard");
  }

  public AnthropicEncodedRequest encode(ProviderRequest request, ProviderDescriptor descriptor) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");

    // native protocolOptions 是合并起点：运行时所有权字段由本编码器写入，其余官方顶层字段原样保留
    ObjectNode root = request.variant().protocolOptions().copyOptions();
    rejectRuntimeOwnedOptions(root);

    root.put("model", request.model().modelId());

    int maxTokens = request.outputTokens();
    root.put("max_tokens", maxTokens);
    root.put("stream", true);

    EffortPlan effort = EffortPlan.resolve(request);
    AnthropicReasoningEffort.applyReasoningParameters(
        root, request, effort.topLevelEffort(), effort.messageLevelEffort());

    boolean deferredToolLane =
        request.model().midConversationSystemMessages() && request.baselineHasClientTools();
    if (request.baselineHasClientTools() && !deferredToolLane) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "client tool baseline requires mid-conversation system-message capability");
    }
    boolean hasInlineToolChanges =
        AnthropicReasoningEffort.hasInlineToolChanges(request.messages());
    List<String> betaFeatures =
        AnthropicReasoningEffort.resolveBetaFeatures(
            effort.messageLevelThinking(), deferredToolLane || hasInlineToolChanges);

    ArrayNode toolsArray =
        AnthropicToolsEncoder.mergeTools(root, request.tools(), deferredToolLane);
    ArrayNode unmarkedSystemArray = encodeSystemInstruction(request.systemInstruction());

    AnthropicWireMessages.Result wire =
        AnthropicWireMessages.encode(request.messages(), descriptor, request.model().modelId());
    ArrayNode wireMessagesArray = wire.messages();

    // 注入 cache marker
    AnthropicCacheMarkers.apply(
        request.cacheControl(),
        wireMessagesArray,
        toolsArray,
        unmarkedSystemArray,
        wire.cacheEndpoints(),
        deferredToolLane);
    if (effort.messageLevelEffort()) {
      wireMessagesArray =
          AnthropicReasoningEffort.applyMessageLevelEffort(
              wireMessagesArray, wire.efforts(), effort.currentEffort(), effort.topLevelEffort());
    }

    if (!toolsArray.isEmpty()) {
      root.set("tools", toolsArray);
    }
    root.set("system", unmarkedSystemArray);
    root.set("messages", wireMessagesArray);

    byte[] utf8Bytes;
    try {
      utf8Bytes = OBJECT_MAPPER.writeValueAsBytes(root);
    } catch (JacksonException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "failed to serialize Anthropic request body");
    }

    bodySizeGuard.enforce(utf8Bytes);

    return new AnthropicEncodedRequest(utf8Bytes, betaFeatures);
  }

  /** 系统指令是请求唯一的顶层 system；编码为单块数组以便在 SYSTEM 边界打缓存标记。 */
  private static ArrayNode encodeSystemInstruction(String systemInstruction) {
    ArrayNode unmarkedSystemArray = NODES.arrayNode();
    ObjectNode systemBlock = NODES.objectNode();
    systemBlock.put("type", "text");
    systemBlock.put("text", systemInstruction);
    unmarkedSystemArray.add(systemBlock);
    return unmarkedSystemArray;
  }

  /** 本轮的推理等级决策：是否按消息级别编码、顶层等级与声明历史中的首个等级。 */
  private record EffortPlan(
      String currentEffort,
      boolean messageLevelThinking,
      boolean messageLevelEffort,
      String topLevelEffort) {

    static EffortPlan resolve(ProviderRequest request) {
      String currentEffort = request.variant() == null ? null : request.variant().reasoningEffort();
      boolean currentEffortOff = request.variant() != null && request.variant().reasoningOff();
      String firstStoredEffort = AnthropicReasoningEffort.firstStoredEffort(request.messages());
      boolean messageLevelThinking =
          request.model().reasoning() && request.model().messageLevelEffort() && !currentEffortOff;
      boolean messageLevelEffort =
          messageLevelThinking && (currentEffort != null || firstStoredEffort != null);
      String topLevelEffort =
          messageLevelEffort && firstStoredEffort != null ? firstStoredEffort : currentEffort;
      return new EffortPlan(
          currentEffort, messageLevelThinking, messageLevelEffort, topLevelEffort);
    }
  }

  /** 声明历史中存储的每个 effort 变化，并把当前等级放在请求前缀末尾。 */
  static ArrayNode applyMessageLevelEffort(
      ArrayNode messages, List<String> efforts, String currentEffort, String topLevelEffort) {
    return AnthropicReasoningEffort.applyMessageLevelEffort(
        messages, efforts, currentEffort, topLevelEffort);
  }

  /** 拒绝 native options 覆盖运行时所有权字段：错误只报字段名，绝不回显 value。 */
  private static void rejectRuntimeOwnedOptions(ObjectNode options) {
    for (String field : RUNTIME_OWNED_OPTION_FIELDS) {
      if (options.has(field)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions must not override runtime field: " + field);
      }
    }
  }
}

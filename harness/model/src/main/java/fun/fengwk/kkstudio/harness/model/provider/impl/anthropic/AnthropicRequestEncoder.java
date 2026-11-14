package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemHostedToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolRemovalBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.RequestBodySizeGuard;

import java.util.ArrayList;
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

    String currentEffort = request.variant() == null ? null : request.variant().reasoningEffort();
    boolean currentEffortOff = request.variant() != null && request.variant().reasoningOff();
    String firstStoredEffort = firstStoredEffort(request.messages());
    boolean messageLevelThinking =
        request.model().reasoning() && request.model().messageLevelEffort() && !currentEffortOff;
    boolean messageLevelEffort =
        messageLevelThinking && (currentEffort != null || firstStoredEffort != null);
    String topLevelEffort =
        messageLevelEffort && firstStoredEffort != null ? firstStoredEffort : currentEffort;
    applyReasoningParameters(root, request, topLevelEffort, messageLevelEffort);

    boolean deferredToolLane =
        request.model().midConversationSystemMessages() && request.baselineHasClientTools();
    if (request.baselineHasClientTools() && !deferredToolLane) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "client tool baseline requires mid-conversation system-message capability");
    }
    boolean hasInlineToolChanges = hasInlineToolChanges(request.messages());
    List<String> betaFeatures =
        resolveBetaFeatures(messageLevelThinking, deferredToolLane || hasInlineToolChanges);

    ArrayNode toolsArray =
        AnthropicToolsEncoder.mergeTools(root, request.tools(), deferredToolLane);

    // 系统指令是请求唯一的顶层 system；编码为单块数组以便在 SYSTEM 边界打缓存标记。
    ArrayNode unmarkedSystemArray = NODES.arrayNode();
    ObjectNode systemBlock = NODES.objectNode();
    systemBlock.put("type", "text");
    systemBlock.put("text", request.systemInstruction());
    unmarkedSystemArray.add(systemBlock);

    // 从左到右构建 wire messages，并记录可用于缓存标记的消息端点
    ArrayNode wireMessagesArray = NODES.arrayNode();
    List<String> messageEfforts = new ArrayList<>();
    List<ObjectNode> cacheEndpoints = new ArrayList<>();
    ObjectNode lastEligible = null;
    for (ProviderMessage msg : request.messages()) {
      if (msg.role() == ProviderMessageRole.ASSISTANT) {
        // assistant 之前的完整前缀就是上次模型请求的端点，不受本轮新增块数量影响。
        AnthropicCacheMarkers.addEndpoint(cacheEndpoints, lastEligible);
        AnthropicEncodedAssistant assistant =
            AnthropicAssistantReplay.encodeMessage(msg, descriptor, request.model().modelId());
        // compaction 回放代表已经摘要完成的历史：durable 校验完成后清空已编码消息，
        // 使 compaction assistant message 成为首条消息、被摘要历史整体省略。
        if (assistant.resetsHistory()) {
          wireMessagesArray.removeAll();
          messageEfforts.clear();
          cacheEndpoints.clear();
          lastEligible = null;
        }
        wireMessagesArray.add(assistant.node());
      } else if (msg.role() == ProviderMessageRole.USER) {
        wireMessagesArray.add(AnthropicContentEncoder.encodeUserMessage(msg));
      } else if (msg.role() == ProviderMessageRole.TOOL) {
        wireMessagesArray.add(AnthropicContentEncoder.encodeToolMessage(msg));
      } else if (msg.role() == ProviderMessageRole.SYSTEM) {
        AnthropicCacheMarkers.addEndpoint(cacheEndpoints, lastEligible);
        wireMessagesArray.add(AnthropicToolsEncoder.encodeSystemMessage(msg));
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unexpected message role: " + msg.role());
      }
      messageEfforts.add(msg.reasoningEffort());
      ObjectNode messageEndpoint =
          AnthropicCacheMarkers.latestEligibleBlock(
              wireMessagesArray.get(wireMessagesArray.size() - 1).path("content"));
      if (messageEndpoint != null) {
        lastEligible = messageEndpoint;
      }
    }
    AnthropicCacheMarkers.addEndpoint(cacheEndpoints, lastEligible);

    // 注入 cache marker
    AnthropicCacheMarkers.apply(
        request.cacheControl(),
        wireMessagesArray,
        toolsArray,
        unmarkedSystemArray,
        cacheEndpoints,
        deferredToolLane);
    if (messageLevelEffort) {
      wireMessagesArray =
          applyMessageLevelEffort(wireMessagesArray, messageEfforts, currentEffort, topLevelEffort);
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

  private static boolean hasInlineToolChanges(List<ProviderMessage> messages) {
    for (ProviderMessage message : messages) {
      if (message.role() != ProviderMessageRole.SYSTEM) {
        continue;
      }
      for (ProviderContentBlock content : message.contents()) {
        if (content instanceof ProviderSystemToolAdditionBlock
            || content instanceof ProviderSystemHostedToolAdditionBlock
            || content instanceof ProviderSystemToolRemovalBlock) {
          return true;
        }
      }
    }
    return false;
  }

  /** Add the beta support required by message-level reasoning and inline tool changes. */
  private static List<String> resolveBetaFeatures(
      boolean messageLevelThinking, boolean inlineTools) {
    List<String> betaFeatures = new ArrayList<>();
    if (messageLevelThinking) {
      betaFeatures.add(OUTPUT_CONFIG_BETA);
      betaFeatures.add(THINKING_BINDING_BETA);
    }
    if (inlineTools) {
      betaFeatures.add(INLINE_TOOLS_BETA);
    }
    return List.copyOf(betaFeatures);
  }

  private static String firstStoredEffort(List<ProviderMessage> messages) {
    for (ProviderMessage message : messages) {
      if (message.role() == ProviderMessageRole.ASSISTANT && hasEffort(message.reasoningEffort())) {
        return message.reasoningEffort();
      }
    }
    return null;
  }

  /** 声明历史中存储的每个 effort 变化，并把当前等级放在请求前缀末尾。 */
  static ArrayNode applyMessageLevelEffort(
      ArrayNode messages, List<String> efforts, String currentEffort, String topLevelEffort) {
    if (messages.size() != efforts.size()) {
      throw new IllegalArgumentException("message efforts must align with encoded messages");
    }
    ArrayNode result = NODES.arrayNode();
    String declared = topLevelEffort;
    for (int i = 0; i < messages.size(); i++) {
      ObjectNode message = (ObjectNode) messages.get(i);
      String effort = efforts.get(i);
      if ("assistant".equals(message.path("role").asString())
          && hasEffort(effort)
          && !Objects.equals(declared, effort)) {
        addEffortControl(result, effort);
        declared = effort;
      }
      result.add(message);
    }
    if (hasEffort(currentEffort) && !Objects.equals(declared, currentEffort)) {
      addEffortControl(result, currentEffort);
    }
    return result;
  }

  private static boolean hasEffort(String effort) {
    return effort != null && !effort.isBlank() && !effort.equals("off");
  }

  private static void addEffortControl(ArrayNode messages, String effort) {
    if (!messages.isEmpty()
        && "system".equals(messages.get(messages.size() - 1).path("role").asString())) {
      JsonNode existing = messages.get(messages.size() - 1).get("output_config");
      ObjectNode outputConfig =
          existing instanceof ObjectNode object
              ? object
              : ((ObjectNode) messages.get(messages.size() - 1)).putObject("output_config");
      outputConfig.put("effort", effort);
    } else {
      ObjectNode node = NODES.objectNode();
      node.put("role", "system");
      node.putArray("content");
      node.putObject("output_config").put("effort", effort);
      messages.add(node);
    }
  }

  /**
   * Encodes effort using the thread's stable top-level level; message-level models additionally
   * bind thinking blocks to the unchanged prefix.
   */
  private static void applyReasoningParameters(
      ObjectNode root, ProviderRequest request, String topLevelEffort, boolean messageLevelEffort) {
    if (!request.model().reasoning()) {
      return;
    }
    if (request.variant() != null && request.variant().reasoningOff()) {
      if (root.has("thinking")) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions must not override runtime field: thinking");
      }
      root.putObject("thinking").put("type", "disabled");
      return;
    }
    if (!hasEffort(topLevelEffort)) {
      return;
    }
    if (root.has("thinking")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions must not override runtime field: thinking");
    }

    ObjectNode thinking = root.putObject("thinking");
    thinking.put("type", "adaptive");
    thinking.put("display", "summarized");
    if (messageLevelEffort) {
      thinking.putObject("block_binding").put("prefix_mismatch_behavior", "drop_block");
    }

    ObjectNode outputConfig = mergeOutputConfig(root);
    outputConfig.put("effort", topLevelEffort);
  }

  /** 以 native {@code output_config} 为起点合并 runtime effort，保留 format 等其余官方子字段。 */
  private static ObjectNode mergeOutputConfig(ObjectNode root) {
    JsonNode nativeOutputConfig = root.get("output_config");
    if (nativeOutputConfig == null) {
      return root.putObject("output_config");
    }
    if (!nativeOutputConfig.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "protocolOptions output_config must be a JSON object");
    }
    if (nativeOutputConfig.has("effort")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions must not override runtime field: output_config.effort");
    }
    return (ObjectNode) nativeOutputConfig;
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

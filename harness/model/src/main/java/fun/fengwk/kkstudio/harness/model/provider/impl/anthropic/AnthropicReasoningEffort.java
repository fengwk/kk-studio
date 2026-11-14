package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemHostedToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolRemovalBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Anthropic Messages 的推理参数编码与 beta 能力声明。
 *
 * <p>thinking 是运行时所有权字段：{@code off} 下发 {@code disabled}，其余已知 effort 下发 {@code adaptive} 并把当前等级写入
 * {@code output_config.effort}；支持消息级 effort 的模型另外把 thinking block 绑定到不变前缀。中途 system 消息里的工具变更同样需要
 * {@code inline-tools} beta。
 */
final class AnthropicReasoningEffort {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final String OUTPUT_CONFIG_BETA = "mid-conversation-output-config-2026-07-01";
  private static final String THINKING_BINDING_BETA = "thinking-binding-controls-2026-08-01";
  private static final String INLINE_TOOLS_BETA = "inline-tools-2026-09-15";

  private AnthropicReasoningEffort() {}

  static boolean hasInlineToolChanges(List<ProviderMessage> messages) {
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

  /** 消息级推理与内联工具变更各自需要的 beta 能力。 */
  static List<String> resolveBetaFeatures(boolean messageLevelThinking, boolean inlineTools) {
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

  static String firstStoredEffort(List<ProviderMessage> messages) {
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
  static void applyReasoningParameters(
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
}

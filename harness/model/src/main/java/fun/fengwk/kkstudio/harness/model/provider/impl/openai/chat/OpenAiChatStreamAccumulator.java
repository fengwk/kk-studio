package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamState;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.OpenAiBackendCapabilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * OpenAI Chat Completions SSE 流式事件状态机与响应累积器。
 *
 * <p>支持 content、tool_calls fragments、reasoning_content delta、reasoning_details、 尾部 usage、终态校验、截断诊断及
 * native replay 构造。
 */
final class OpenAiChatStreamAccumulator extends ProviderStreamState {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final String DONE_DATA = "[DONE]";

  private static final Set<String> VALID_FINISH_REASONS =
      Set.of("stop", "tool_calls", "length", "content_filter");

  private final ProviderRequest request;
  private final ProviderDescriptor descriptor;
  private final ProviderStreamBridge bridge;

  private final OpenAiChatNativeAssistantMessage nativeMessage =
      new OpenAiChatNativeAssistantMessage();
  private final OpenAiChatUsage usage = new OpenAiChatUsage();
  private final OpenAiChatToolCallStream toolCalls;

  private String messageId = null;
  private boolean seenDone = false;

  /** {@code choices[0]} 首个有效 finish_reason 之后语义封闭：只允许无语义尾帧，绝不再改写任何已累积事实。 */
  private boolean choiceFinalized = false;

  /** 冻结时的原始 finish_reason 文本与 choices[0] index，用于判定重复终止标记是否为幂等冗余。 */
  private String finalizedFinishReason = null;

  private int finalizedChoiceIndex = 0;

  private final StringBuilder content = new StringBuilder();
  private final StringBuilder refusal = new StringBuilder();
  private final StringBuilder reasoningContent = new StringBuilder();
  private JsonNode reasoningDetails = null;

  OpenAiChatStreamAccumulator(
      ProviderRequest request, ProviderDescriptor descriptor, ProviderStreamBridge bridge) {
    this.request = Objects.requireNonNull(request, "request");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
    this.toolCalls = new OpenAiChatToolCallStream(this.bridge, nativeMessage);
  }

  void handleData(String data) {
    if (bridge.isClosed() || data == null || data.isBlank()) {
      return;
    }
    String trimmed = data.trim();
    if (DONE_DATA.equals(trimmed)) {
      seenDone = true;
      return;
    }
    if (seenDone) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "semantic payload received after [DONE]");
    }

    JsonNode root = ProviderJsonHelper.parseSseJson(trimmed, "invalid SSE JSON payload");
    if (!root.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "SSE data must be a JSON object");
    }
    if (root.has("error")) {
      throw OpenAiChatErrorMapper.mapSseErrorEnvelope(root);
    }
    readMessageMetadata(root);
    readUsage(root);
    readChoices(root);
  }

  private void readMessageMetadata(JsonNode root) {
    if (root.has("id") && root.get("id").isString()) {
      this.messageId = root.get("id").asString("");
    }
    if (root.has("service_tier") && root.get("service_tier").isString()) {
      this.serviceTier = root.get("service_tier").asString("");
    }
  }

  /** 流式中间块允许 usage 显式为 null；其他非 object 形态严格拒绝。 */
  private void readUsage(JsonNode root) {
    if (!root.has("usage") || root.get("usage").isNull()) {
      return;
    }
    if (!root.get("usage").isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "usage must be a JSON object");
    }
    usage.replaceWith(root.get("usage"));
  }

  private void readChoices(JsonNode root) {
    if (!root.has("choices") || !root.get("choices").isArray()) {
      return;
    }
    ArrayNode choices = (ArrayNode) root.get("choices");
    if (choices.isEmpty() || !choices.get(0).isObject()) {
      return;
    }
    parseChoice(choices.get(0));
  }

  private void parseChoice(JsonNode choice) {
    if (choiceFinalized) {
      verifyIdempotentTail(choice);
      return;
    }
    if (choice.has("delta") && choice.get("delta").isObject()) {
      consumeDelta(choice.get("delta"));
    }
    // 首个有效 finish_reason 封闭该 choice 的语义；同帧尾随 content 已在上方正常累积后才封闭
    if (carriesFinishReason(choice)) {
      finalizeChoice(choice);
    }
  }

  /**
   * 语义终态已封闭：重复终止标记只有与冻结结果完全一致（同一有效 finish_reason、同一有效 choice index、无 delta）才是幂等冗余，不追加内容/增量/改写
   * replay；任何语义 delta、变更 reason 或不同 index 都 fail closed。
   */
  private void verifyIdempotentTail(JsonNode choice) {
    boolean carriesReason = carriesFinishReason(choice);
    if (!isEmptyValue(choice.get("delta"))) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "semantic delta received after finalized choice");
    }
    if (!carriesReason) {
      return;
    }
    String repeatedReason = choice.get("finish_reason").stringValue();
    if (!repeatedReason.equals(finalizedFinishReason)
        || choiceIndexOf(choice) != finalizedChoiceIndex) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "conflicting finish_reason after finalized choice");
    }
  }

  /** 合并一帧 delta：native 槽位先合并，规范化通道只读取已知增量字段。 */
  private void consumeDelta(JsonNode delta) {
    nativeMessage.mergeDelta(delta);
    appendVisibleTextDelta(delta.get("content"), content);
    // refusal 是协议中的成功助手输出，归一化到可见文本通道
    appendVisibleTextDelta(delta.get("refusal"), refusal);
    appendThinkingDelta(delta.get("reasoning_content"));
    if (delta.has("reasoning_details") && !delta.get("reasoning_details").isNull()) {
      mergeReasoningDetails(delta.get("reasoning_details"));
    }
    if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
      toolCalls.mergeDelta((ArrayNode) delta.get("tool_calls"));
    }
  }

  private void appendVisibleTextDelta(JsonNode node, StringBuilder buffer) {
    String delta = stringValue(node);
    if (delta.isEmpty()) {
      return;
    }
    buffer.append(delta);
    bridge.emitEvent(new ProviderStreamEvent.TextDelta(delta));
  }

  private void appendThinkingDelta(JsonNode node) {
    String delta = stringValue(node);
    if (delta.isEmpty()) {
      return;
    }
    reasoningContent.append(delta);
    bridge.emitEvent(new ProviderStreamEvent.ThinkingDelta(delta));
  }

  private static String stringValue(JsonNode node) {
    return node != null && node.isString() ? node.stringValue() : "";
  }

  /** reasoning_details 只在 replay 中原样保留；连续的数组片段按顺序拼接，其余形态整体覆盖。 */
  private void mergeReasoningDetails(JsonNode incoming) {
    if (reasoningDetails == null) {
      reasoningDetails = incoming.deepCopy();
      return;
    }
    if (reasoningDetails.isArray() && incoming.isArray()) {
      ArrayNode current = (ArrayNode) reasoningDetails;
      for (JsonNode item : incoming) {
        current.add(item.deepCopy());
      }
      return;
    }
    reasoningDetails = incoming.deepCopy();
  }

  private void finalizeChoice(JsonNode choice) {
    String reasonText = choice.get("finish_reason").stringValue();
    if (!VALID_FINISH_REASONS.contains(reasonText)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unsupported finish_reason: " + reasonText);
    }
    this.stopReason = mapFinishReason(reasonText);
    this.finalizedFinishReason = reasonText;
    this.finalizedChoiceIndex = choiceIndexOf(choice);
    this.choiceFinalized = true;
  }

  private static GenerationStopReason mapFinishReason(String reason) {
    return switch (reason) {
      case "stop", "tool_calls" -> GenerationStopReason.COMPLETE;
      case "length" -> GenerationStopReason.LENGTH;
      case "content_filter" -> GenerationStopReason.FILTERED;
      default -> throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unsupported finish_reason: " + reason);
    };
  }

  /** 缺失 index 视为主 choice 默认 0；一旦提供，必须是可转 int 的非负整数，否则 fail closed 且不回显原始值。 */
  private static int choiceIndexOf(JsonNode choice) {
    JsonNode index = choice.get("index");
    if (index == null) {
      return 0;
    }
    if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "choice index must be a non-negative integer");
    }
    return index.intValue();
  }

  /**
   * 判定 choice 是否携带有效 finish_reason：非 null 且非空白字符串。非字符串形态一律 fail closed，绝不被 {@code asString} 静默吞掉；显式
   * null 与空白字符串属于无语义尾帧。
   */
  private static boolean carriesFinishReason(JsonNode choice) {
    JsonNode node = choice.get("finish_reason");
    if (node == null || node.isNull()) {
      return false;
    }
    if (!node.isString()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "finish_reason must be a string");
    }
    return !node.stringValue().isBlank();
  }

  /** 无语义值：缺失、null、空字符串、空容器，或所有子节点都没有语义。 */
  private static boolean isEmptyValue(JsonNode node) {
    if (node == null || node.isNull()) {
      return true;
    }
    if (node.isString()) {
      return node.stringValue().isEmpty();
    }
    if (node.isArray() || node.isObject()) {
      for (JsonNode child : node) {
        if (!isEmptyValue(child)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  ProviderCompletion finish() {
    requireCompleteStreamFacts();
    boolean canReplay = stopReason == GenerationStopReason.COMPLETE;
    OpenAiChatToolCallStream.ToolCalls resolved = resolveToolCalls();
    if (!resolved.diagnostics().isEmpty()) {
      canReplay = false;
    }
    ModelUsage modelUsage = usage.toModelUsage(cacheWriteTtlSeconds());

    List<ProviderContentBlock> blocks = new ArrayList<>();
    addSemanticBlocks(blocks, resolved.calls(), canReplay);
    if (canReplay) {
      addNativeReplayBlock(blocks, resolved.calls());
    }
    ProviderResponse response =
        new ProviderResponse(
            blocks,
            stopReason,
            modelUsage,
            messageId,
            serviceTier,
            usage.rawUsageJson(),
            Collections.unmodifiableList(resolved.diagnostics()),
            null);
    return new ProviderCompletion(response);
  }

  private Long cacheWriteTtlSeconds() {
    return OpenAiBackendCapabilities.reliableWriteTtlSeconds(
        descriptor, request.model().modelId(), request.cacheControl().retention());
  }

  private void requireCompleteStreamFacts() {
    if (!usage.isPresent()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "missing complete usage: OpenAI Chat model " + request.model().modelId());
    }
    if (!seenDone) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "stream completed without [DONE]");
    }
    if (stopReason == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "stream completed without valid finish_reason");
    }
  }

  /** FILTERED 响应不携带任何工具调用或诊断；其余终态按 stop reason 校验每个流内工具调用。 */
  private OpenAiChatToolCallStream.ToolCalls resolveToolCalls() {
    if (stopReason == GenerationStopReason.FILTERED) {
      return new OpenAiChatToolCallStream.ToolCalls(List.of(), List.of());
    }
    return toolCalls.resolve(stopReason);
  }

  private void addSemanticBlocks(
      List<ProviderContentBlock> blocks, List<ProviderToolCall> calls, boolean canReplay) {
    ObjectNode reasoningFields = NODES.objectNode();
    if (reasoningDetails != null && !reasoningDetails.isNull()) {
      reasoningFields.set("reasoning_details", reasoningDetails.deepCopy());
    }
    if (!reasoningContent.isEmpty() || !reasoningFields.isEmpty()) {
      blocks.add(
          new ProviderThinkingBlock(
              reasoningContent.toString(), blockData(reasoningFields, canReplay)));
    }
    if (!content.isEmpty()) {
      blocks.add(
          new ProviderTextBlock(content.toString(), blockData(NODES.objectNode(), canReplay)));
    }
    if (!refusal.isEmpty()) {
      ObjectNode refusalFields = NODES.objectNode();
      refusalFields.put("refusal", true);
      blocks.add(new ProviderTextBlock(refusal.toString(), blockData(refusalFields, canReplay)));
    }
    for (ProviderToolCall call : calls) {
      blocks.add(
          new ProviderToolCallBlock(call, blockData(toolCalls.nativeFields(call), canReplay)));
    }
    if (content.isEmpty() && refusal.isEmpty() && calls.isEmpty()) {
      blocks.add(new ProviderTextBlock("", blockData(NODES.objectNode(), canReplay)));
    }
  }

  private void addNativeReplayBlock(
      List<ProviderContentBlock> blocks, List<ProviderToolCall> calls) {
    ObjectNode fields = nativeMessage.replayFields(!calls.isEmpty());
    if (!fields.isEmpty()) {
      blocks.add(new ProviderOpaqueBlock("openai_chat_message", blockData(fields, true)));
    }
  }

  private ProviderBlockData blockData(ObjectNode nativeFields, boolean replayable) {
    if (!replayable) {
      return null;
    }
    JsonNode refusalField = nativeFields.get("refusal");
    boolean isRefusal =
        refusalField != null && refusalField.isBoolean() && refusalField.booleanValue();
    ObjectNode replayFields = nativeFields.deepCopy();
    replayFields.remove("refusal");
    return new ProviderBlockData(
        ProviderReplayFormat.OPENAI_CHAT,
        descriptor.affinity(request.model().modelId()),
        null,
        null,
        null,
        null,
        null,
        isRefusal,
        replayFields);
  }
}

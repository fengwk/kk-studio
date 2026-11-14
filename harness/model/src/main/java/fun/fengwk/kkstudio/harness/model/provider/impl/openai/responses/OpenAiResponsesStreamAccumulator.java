package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * OpenAI Responses SSE 流式事件状态机与响应累积器。
 *
 * <p>按协议规范解析文本增量、推理增量与工具调用增量，处理 completed/incomplete/failed 事件， 进行细粒度用量归一化与 native replay 组装：已知
 * message/reasoning/function_call 归一化，其余官方 output item 作为不透明事实保留。
 *
 * <p>每条 transport 帧在进入状态机之前先经 {@link ProviderStreamBridge#emitProtocolEvent} exactly-once
 * 上报原生事实；原生帧不进入 durable checkpoint、realtime 增量或任何内容缓冲。
 */
public final class OpenAiResponsesStreamAccumulator extends ProviderStreamState {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final String USAGE_INTEGER_FORMAT =
      "invalid usage token count in field %s: must be an integral number within long range";
  private static final String USAGE_NEGATIVE_FORMAT =
      "invalid usage token count in field %s: must be non-negative";

  private static final Set<String> KNOWN_SSE_EVENTS =
      Set.of(
          "response.created",
          "response.output_text.delta",
          "response.refusal.delta",
          "response.refusal.done",
          "response.reasoning_text.delta",
          "response.reasoning_summary_text.delta",
          "response.output_item.added",
          "response.function_call_arguments.delta",
          "response.function_call_arguments.done",
          "response.output_item.done",
          "response.completed",
          "response.incomplete",
          "response.failed",
          "response.error",
          "error");

  /** 无法从 transport 帧还原出官方事件名时使用的兜底原生事件名。 */
  private static final String UNKNOWN_PROTOCOL_EVENT_TYPE = "openai.response.event";

  private final ProviderRequest request;
  private final ProviderDescriptor descriptor;
  private final ProviderStreamBridge bridge;

  private boolean terminalReceived = false;
  private String requestId = null;
  private final StringBuilder textBuffer = new StringBuilder();
  private final StringBuilder thinkingBuffer = new StringBuilder();

  // 流内工具调用状态；ordinal 只在首次观察到 function_call 时分配并保持稳定
  private final OpenAiResponsesToolStream tools;

  // 终态回放用有序 output items
  private final List<JsonNode> rawOutputItems = new ArrayList<>();
  // 未知官方 item 在 rawOutputItems 中的槽位：added 先占位，done 覆盖同一槽位，避免重复或丢失
  private final Map<String, Integer> opaqueItemSlots = new LinkedHashMap<>();
  private boolean explicitTerminalOutputProcessed = false;

  // Token 用量
  private JsonNode latestNativeUsageNode = null;
  private long rawInputTokens = 0L;
  private long rawOutputTokens = 0L;
  private long rawTotalTokens = 0L;
  private boolean hasRawTotalTokens = false;
  private long cachedTokens = 0L;
  private long cacheWriteTokens = 0L;
  private Long reasoningTokens = null;
  private ProviderCompletion finishedCompletion = null;

  OpenAiResponsesStreamAccumulator(
      ProviderRequest request, ProviderDescriptor descriptor, ProviderStreamBridge bridge) {
    this.request = Objects.requireNonNull(request, "request");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
    this.tools = new OpenAiResponsesToolStream(this.bridge);
  }

  public OpenAiResponsesStreamAccumulator(
      ProviderRequest request,
      ProviderDescriptor descriptor,
      Consumer<ProviderStreamEvent> eventConsumer) {
    this(request, descriptor, createBridge(eventConsumer));
  }

  private static ProviderStreamBridge createBridge(Consumer<ProviderStreamEvent> eventConsumer) {
    return new ProviderStreamBridge(
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event) {
            if (eventConsumer != null) {
              eventConsumer.accept(event);
            }
          }

          @Override
          public void onComplete(ProviderCompletion completion) {}

          @Override
          public void onError(ProviderException error) {}
        });
  }

  public void handleEvent(String eventName, String data) {
    if (bridge.isClosed()) {
      return;
    }
    // 每条 transport 帧（含 keepalive ping、[DONE] 哨兵与畸形帧）先 exactly-once 上报原生事实，
    // 再进入规范化状态机；failed/error 因此必然是「先 raw 再异常」。
    bridge.emitProtocolEvent(protocolEvent(eventName, data));
    if ("ping".equals(eventName)) {
      return;
    }
    if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
      return;
    }

    JsonNode node = ProviderJsonHelper.parseSseJson(data, "SSE event data is not valid JSON");
    if (!node.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "SSE event data is not a JSON object");
    }

    processEvent(node);
  }

  /**
   * 还原 transport 帧的原生身份：优先 SSE {@code event} 名，其次 payload 的 JSON {@code type}，最后是通用兜底名； {@code
   * [DONE]} 哨兵归一为 {@code done}。只有帧身份被解释，{@code data} 始终原样透出，绝不重写厂商事实。
   */
  private static ProviderProtocolEvent protocolEvent(String eventName, String data) {
    String payload = data == null ? "" : data;
    if (eventName != null && !eventName.isBlank()) {
      return new ProviderProtocolEvent(eventName, payload);
    }
    if ("[DONE]".equals(payload.trim())) {
      return new ProviderProtocolEvent("done", payload);
    }
    JsonNode node = ProviderJsonHelper.tryParseJsonObject(payload);
    if (node != null) {
      JsonNode typeNode = node.get("type");
      if (typeNode != null && typeNode.isString() && !typeNode.stringValue().isBlank()) {
        return new ProviderProtocolEvent(typeNode.stringValue(), payload);
      }
    }
    return new ProviderProtocolEvent(UNKNOWN_PROTOCOL_EVENT_TYPE, payload);
  }

  public void processEvent(JsonNode node) {
    if (node == null || !node.isObject()) {
      return;
    }
    String type = node.path("type").asString("");

    if ("response.failed".equals(type) || "response.error".equals(type) || "error".equals(type)) {
      throw OpenAiResponsesErrorMapper.mapSseErrorEnvelope(node);
    }

    if (!KNOWN_SSE_EVENTS.contains(type)) {
      return;
    }
    if (terminalReceived) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "semantic event received after terminal response");
    }

    switch (type) {
      case "response.created" -> handleCreated(node);
      case "response.output_text.delta", "response.refusal.delta" -> handleTextDelta(node);
      case "response.refusal.done" -> {
        // delta 与 terminal output 已分别承担实时展示和权威终态，done 仅表示该 content part 完成。
      }
      case "response.reasoning_text.delta",
          "response.reasoning_summary_text.delta" -> handleThinkingDelta(node);
      case "response.output_item.added" -> handleOutputItemAdded(node);
      case "response.function_call_arguments.delta" -> handleArgumentsDelta(node);
      case "response.function_call_arguments.done" -> handleArgumentsDone(node);
      case "response.output_item.done" -> handleOutputItemDone(node);
      case "response.completed" -> handleCompleted(node);
      case "response.incomplete" -> handleIncomplete(node);
      default -> {}
    }
  }

  private void handleCreated(JsonNode node) {
    JsonNode respNode = node.get("response");
    if (respNode != null && respNode.isObject()) {
      extractResponseData(respNode, false);
    }
  }

  private void handleTextDelta(JsonNode node) {
    String delta = node.path("delta").asString("");
    if (!delta.isEmpty()) {
      textBuffer.append(delta);
      bridge.emitEvent(new ProviderStreamEvent.TextDelta(delta));
    }
  }

  private void handleThinkingDelta(JsonNode node) {
    String delta = node.path("delta").asString("");
    if (!delta.isEmpty()) {
      thinkingBuffer.append(delta);
      bridge.emitEvent(new ProviderStreamEvent.ThinkingDelta(delta));
    }
  }

  private void handleOutputItemAdded(JsonNode node) {
    JsonNode item = node.get("item");
    if (item == null || !item.isObject()) {
      return;
    }
    String itemType = item.path("type").asString("");
    if ("function_call".equals(itemType)) {
      tools.recordAddedItem(item);
      return;
    }
    // 已知 message/reasoning 的权威表示来自 delta、done 与 terminal output；未知官方 item 只有这条流式事实来源。
    if (!isKnownOutputItemType(itemType)) {
      retainStreamedOpaqueItem(node);
    }
  }

  private void handleArgumentsDelta(JsonNode node) {
    tools.appendArgumentsDelta(node);
  }

  private void handleArgumentsDone(JsonNode node) {
    tools.markArgumentsDone(node);
  }

  private static long usageRemainder(long total, long... parts) {
    long excluded = 0L;
    try {
      for (long part : parts) {
        excluded = Math.addExact(excluded, part);
      }
    } catch (ArithmeticException overflow) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage values overflow");
    }
    if (excluded > total) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage breakdown exceeds total");
    }
    return total - excluded;
  }

  private static void retainStreamedReasoningEncryptedContent(
      ObjectNode terminalItem, List<JsonNode> streamedItems) {
    if (terminalItem.has("encrypted_content")) {
      return;
    }

    JsonNode terminalId = terminalItem.get("id");
    if (terminalId == null || !terminalId.isString() || terminalId.stringValue().isBlank()) {
      return;
    }

    for (JsonNode streamedItem : streamedItems) {
      if (!streamedItem.isObject()
          || !"reasoning".equals(streamedItem.path("type").asString(""))
          || !terminalId.stringValue().equals(streamedItem.path("id").asString(null))) {
        continue;
      }
      JsonNode encryptedContent = streamedItem.get("encrypted_content");
      if (encryptedContent != null
          && encryptedContent.isString()
          && !encryptedContent.stringValue().isBlank()) {
        terminalItem.set("encrypted_content", encryptedContent.deepCopy());
      }
      return;
    }
  }

  /**
   * 记录流式到达的未知官方 item。
   *
   * <p>{@code output_item.added} 先建立槽位：未知 item 没有 delta 等其它语义来源，丢掉就再也回不来；随后到达的 {@code
   * output_item.done} 以更完整的 deepCopy 覆盖同一槽位，成为该 item 的 replay 事实。槽位键优先取 added/done 共享的 {@code
   * output_index}，其次取 item id；两者都缺失的 done 帧仍然追加，绝不静默丢弃。
   */
  private void retainStreamedOpaqueItem(JsonNode node) {
    JsonNode item = node.get("item");
    if (item == null
        || !item.isObject()
        || !OpenAiResponsesReplayBuilder.isOpaqueReplayItem(item)) {
      return;
    }
    String key = opaqueItemSlotKey(node, item);
    Integer slot = key == null ? null : opaqueItemSlots.get(key);
    if (slot != null) {
      rawOutputItems.set(slot, item.deepCopy());
      return;
    }
    if (key != null) {
      opaqueItemSlots.put(key, rawOutputItems.size());
    }
    rawOutputItems.add(item.deepCopy());
  }

  private static String opaqueItemSlotKey(JsonNode node, JsonNode item) {
    JsonNode outputIndex = node.get("output_index");
    if (outputIndex != null && outputIndex.isIntegralNumber()) {
      // 槽位键取索引的完整十进制文字：任意宽度的整数坐标都不会因 int 截断而与其他 item 共用同一槽位。
      return "index:" + outputIndex.bigIntegerValue();
    }
    JsonNode id = item.get("id");
    if (id != null && id.isString() && !id.stringValue().isBlank()) {
      return "id:" + id.stringValue();
    }
    return null;
  }

  private void handleOutputItemDone(JsonNode node) {
    JsonNode item = node.get("item");
    if (item == null || !item.isObject()) {
      return;
    }
    String itemType = item.path("type").asString("");
    if (!isKnownOutputItemType(itemType)) {
      retainStreamedOpaqueItem(node);
      return;
    }
    if ("function_call".equals(itemType)) {
      tools.syncFromItem(item);
    } else if ("reasoning".equals(itemType)) {
      // content 是已知语义输入：捕获阶段即拒绝已知坏形状，避免冻结出下一轮必然失败的 replay。
      OpenAiResponsesReasoningText.requireValidContent(
          item.get("content"), ProviderErrorKind.INVALID_RESPONSE);
    }
    rawOutputItems.add(item.deepCopy());
  }

  private void handleCompleted(JsonNode node) {
    terminalReceived = true;
    stopReason = GenerationStopReason.COMPLETE;
    extractResponseData(node.get("response"), true);
  }

  private void handleIncomplete(JsonNode node) {
    terminalReceived = true;
    JsonNode responseNode = node.get("response");
    String reason = "";
    if (responseNode != null && responseNode.has("incomplete_details")) {
      reason = responseNode.path("incomplete_details").path("reason").asString("");
    }
    if ("content_filter".equals(reason)) {
      stopReason = GenerationStopReason.FILTERED;
    } else {
      stopReason = GenerationStopReason.LENGTH;
    }
    extractResponseData(responseNode, true);
  }

  private static long usageToken(JsonNode node, String fieldName) {
    return ProviderJsonHelper.parseNonNegativeLong(
        node, fieldName, USAGE_INTEGER_FORMAT, USAGE_NEGATIVE_FORMAT);
  }

  private void resetUsage() {
    latestNativeUsageNode = null;
    rawInputTokens = 0L;
    rawOutputTokens = 0L;
    hasRawTotalTokens = false;
    rawTotalTokens = 0L;
    cachedTokens = 0L;
    cacheWriteTokens = 0L;
    reasoningTokens = null;
  }

  private void extractResponseData(JsonNode responseNode, boolean isTerminal) {
    if (responseNode == null || !responseNode.isObject()) {
      return;
    }
    applyResponseIdentity(responseNode);
    applyUsage(responseNode);
    if (!responseNode.has("output")) {
      return;
    }
    JsonNode outputNode = responseNode.get("output");
    if (outputNode == null || outputNode.isNull() || !outputNode.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "response output must be a JSON array");
    }
    if (isTerminal) {
      ingestTerminalOutput(outputNode);
    }
  }

  /** 响应身份只由非空字符串升级；缺失字段保留此前观测值。 */
  private void applyResponseIdentity(JsonNode responseNode) {
    if (responseNode.has("id") && !responseNode.path("id").asString("").isBlank()) {
      requestId = responseNode.path("id").asString("");
    }
    if (responseNode.has("service_tier")
        && !responseNode.path("service_tier").asString("").isBlank()) {
      serviceTier = responseNode.path("service_tier").asString("");
    }
  }

  private void applyUsage(JsonNode responseNode) {
    if (!responseNode.has("usage")) {
      return;
    }
    JsonNode usageNode = responseNode.get("usage");
    if (usageNode == null || usageNode.isNull()) {
      resetUsage();
      return;
    }
    if (!usageNode.isObject()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "usage is not a JSON object");
    }
    resetUsage();
    latestNativeUsageNode = usageNode.deepCopy();
    if (!usageNode.has("input_tokens") || !usageNode.has("output_tokens")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "missing complete usage: OpenAI Responses model " + request.model().modelId());
    }
    rawInputTokens = usageToken(usageNode.get("input_tokens"), "input_tokens");
    rawOutputTokens = usageToken(usageNode.get("output_tokens"), "output_tokens");
    JsonNode totalNode = usageNode.get("total_tokens");
    if (totalNode != null && !totalNode.isNull()) {
      hasRawTotalTokens = true;
      rawTotalTokens = usageToken(totalNode, "total_tokens");
    }
    applyInputTokenDetails(usageNode.get("input_tokens_details"));
    applyOutputTokenDetails(usageNode.get("output_tokens_details"));
  }

  private void applyInputTokenDetails(JsonNode details) {
    if (details == null || details.isNull()) {
      return;
    }
    if (!details.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "input_tokens_details is not a JSON object");
    }
    cachedTokens =
        details.has("cached_tokens")
            ? usageToken(details.get("cached_tokens"), "cached_tokens")
            : 0L;
    cacheWriteTokens =
        details.has("cache_write_tokens")
            ? usageToken(details.get("cache_write_tokens"), "cache_write_tokens")
            : 0L;
  }

  private void applyOutputTokenDetails(JsonNode details) {
    if (details == null || details.isNull()) {
      return;
    }
    if (!details.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "output_tokens_details is not a JSON object");
    }
    if (details.has("reasoning_tokens") && !details.get("reasoning_tokens").isNull()) {
      reasoningTokens = usageToken(details.get("reasoning_tokens"), "reasoning_tokens");
    }
  }

  /** 显式终态 output 是权威事实：清空流内草稿后按 item 顺序重建文本、思考与工具状态。 */
  private void ingestTerminalOutput(JsonNode outputNode) {
    explicitTerminalOutputProcessed = true;
    List<JsonNode> priorStreamedItems = new ArrayList<>(rawOutputItems);
    String priorStreamedThinking = thinkingBuffer.toString();
    rawOutputItems.clear();
    opaqueItemSlots.clear();
    tools.clear();
    textBuffer.setLength(0);
    thinkingBuffer.setLength(0);
    for (JsonNode itemNode : outputNode) {
      if (!itemNode.isObject()) {
        rawOutputItems.add(itemNode.deepCopy());
        continue;
      }
      ingestTerminalItem((ObjectNode) itemNode.deepCopy(), priorStreamedItems);
    }
    rebuildThinkingFromItems(priorStreamedThinking);
  }

  private void ingestTerminalItem(ObjectNode item, List<JsonNode> priorStreamedItems) {
    String itemType = item.path("type").asString("");
    if ("reasoning".equals(itemType)) {
      retainStreamedReasoningEncryptedContent(item, priorStreamedItems);
      // content 是已知语义输入：捕获阶段即拒绝已知坏形状，避免冻结出下一轮必然失败的 replay。
      OpenAiResponsesReasoningText.requireValidContent(
          item.get("content"), ProviderErrorKind.INVALID_RESPONSE);
    }
    rawOutputItems.add(item);
    if ("function_call".equals(itemType)) {
      tools.syncFromItem(item);
    } else if ("message".equals(itemType)) {
      appendMessageContent(item.get("content"));
    }
  }

  ProviderCompletion finish() {
    if (finishedCompletion != null) {
      return finishedCompletion;
    }
    requireTerminalFacts();
    fillAuthoritativeContent();

    OpenAiResponsesToolStream.ToolCalls resolved = resolveToolCalls();
    ModelUsage usage = normalizeUsage();
    String rawUsageJson = serializeNativeUsage();
    List<ProviderContentBlock> responseBlocks = buildResponseBlocks(resolved);

    ProviderResponse response =
        new ProviderResponse(
            responseBlocks,
            stopReason,
            usage,
            requestId,
            serviceTier,
            rawUsageJson,
            Collections.unmodifiableList(resolved.diagnostics()),
            null,
            null);

    finishedCompletion = new ProviderCompletion(response);
    return finishedCompletion;
  }

  /** 终态事实是完成响应的最低前提：提前 EOF、未知 stopReason 与缺失 usage 一律拒绝。 */
  private void requireTerminalFacts() {
    if (!terminalReceived) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "OpenAI Responses stream ended unexpectedly before complete message received (premature EOF)");
    }
    if (stopReason == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "missing stopReason in OpenAI Responses response");
    }
    if (latestNativeUsageNode == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "missing complete usage: OpenAI Responses model " + request.model().modelId());
    }
  }

  /** 终态未显式给出 output 时，从流内 item 补全权威文本与思考内容。 */
  private void fillAuthoritativeContent() {
    if (explicitTerminalOutputProcessed) {
      return;
    }
    for (JsonNode item : rawOutputItems) {
      String itemType = item.path("type").asString("");
      if ("message".equals(itemType)) {
        JsonNode content = item.get("content");
        if (content != null && textBuffer.isEmpty()) {
          appendMessageContent(content);
        }
      }
    }
    rebuildThinkingFromItems(thinkingBuffer.toString());
  }

  /** FILTERED 不承载可执行工具调用；其余终态由工具流按完整性裁决，任何截断诊断都阻止 replay 冻结。 */
  private OpenAiResponsesToolStream.ToolCalls resolveToolCalls() {
    if (stopReason == GenerationStopReason.FILTERED) {
      return new OpenAiResponsesToolStream.ToolCalls(List.of(), List.of());
    }
    return tools.resolve(stopReason);
  }

  private boolean canReplay(OpenAiResponsesToolStream.ToolCalls resolved) {
    return (stopReason == GenerationStopReason.COMPLETE
            || stopReason == GenerationStopReason.LENGTH)
        && resolved.diagnostics().isEmpty();
  }

  /** Token 用量归一化：total_tokens 缺失时为 null，不凭空合成；输出总量包含 reasoning。 */
  private ModelUsage normalizeUsage() {
    long ordinaryInput = usageRemainder(rawInputTokens, cachedTokens, cacheWriteTokens);
    Long providerTotalTokens = hasRawTotalTokens ? rawTotalTokens : null;
    try {
      return new ModelUsage(
          ordinaryInput,
          rawOutputTokens,
          cachedTokens,
          cacheWriteTokens,
          Map.of(),
          reasoningTokens,
          providerTotalTokens);
    } catch (IllegalArgumentException invalidUsage) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage values are inconsistent");
    }
  }

  /** 完整 usage 已在终态校验；原生结构高保真保留，不伪造字段。 */
  private String serializeNativeUsage() {
    try {
      return OBJECT_MAPPER.writeValueAsString(latestNativeUsageNode);
    } catch (Exception e) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "failed to serialize raw usage JSON");
    }
  }

  /** replay 冻结优先；没有可回放的原生事实时按「思考 → 文本 → 工具调用」语义回退。 */
  private List<ProviderContentBlock> buildResponseBlocks(
      OpenAiResponsesToolStream.ToolCalls resolved) {
    List<ProviderContentBlock> responseBlocks = new ArrayList<>();
    if (canReplay(resolved)) {
      ArrayNode replayOutputArray =
          OpenAiResponsesReplayBuilder.build(
              rawOutputItems,
              explicitTerminalOutputProcessed,
              thinkingBuffer.toString(),
              textBuffer.toString(),
              tools.snapshot());
      // 只含空 reasoning 占位符的 replay 不承载任何原生推理，冻结它没有语义价值，交由下一轮语义编码。
      if (!replayOutputArray.isEmpty()
          && !OpenAiResponsesReplayItems.isEmptyPlaceholderOnly(replayOutputArray)) {
        responseBlocks.addAll(responseBlocks(replayOutputArray, resolved.calls()));
      }
    }
    if (responseBlocks.isEmpty()) {
      if (!thinkingBuffer.isEmpty()) {
        responseBlocks.add(new ProviderThinkingBlock(thinkingBuffer.toString()));
      }
      if (!textBuffer.isEmpty()) {
        responseBlocks.add(new ProviderTextBlock(textBuffer.toString()));
      }
      for (ProviderToolCall call : resolved.calls()) {
        responseBlocks.add(new ProviderToolCallBlock(call));
      }
    }
    return responseBlocks;
  }

  private List<ProviderContentBlock> responseBlocks(
      ArrayNode outputItems, List<ProviderToolCall> toolCalls) {
    Map<String, ProviderToolCall> callsById = new LinkedHashMap<>();
    for (ProviderToolCall call : toolCalls) {
      callsById.put(call.id(), call);
    }
    List<ProviderContentBlock> blocks = new ArrayList<>();
    for (JsonNode item : outputItems) {
      String itemType = item.path("type").asString("");
      if ("message".equals(itemType)) {
        addMessageBlocks(blocks, item);
      } else if ("reasoning".equals(itemType)) {
        addReasoningBlocks(blocks, item);
      } else if ("function_call".equals(itemType)) {
        ProviderToolCall call = callsById.get(item.path("call_id").asString(""));
        if (call == null) {
          ObjectNode fields = NODES.objectNode();
          fields.set("item", item.deepCopy());
          blocks.add(new ProviderOpaqueBlock(itemType, responseBlockData(fields)));
        } else {
          ObjectNode fields = NODES.objectNode();
          fields.put("itemType", itemType);
          fields.set(
              "itemFields", copyFields(item, Set.of("type", "call_id", "name", "arguments")));
          blocks.add(new ProviderToolCallBlock(call, responseBlockData(fields)));
        }
      } else {
        ObjectNode fields = NODES.objectNode();
        fields.set("item", item.deepCopy());
        blocks.add(new ProviderOpaqueBlock(itemType, responseBlockData(fields)));
      }
    }
    return blocks;
  }

  private void addMessageBlocks(List<ProviderContentBlock> blocks, JsonNode item) {
    JsonNode content = item.path("content");
    if (!content.isArray() || content.isEmpty()) {
      ObjectNode fields = NODES.objectNode();
      fields.set("item", item.deepCopy());
      blocks.add(new ProviderOpaqueBlock("message", responseBlockData(fields)));
      return;
    }
    ObjectNode itemFields = copyFields(item, Set.of("content"));
    boolean first = true;
    for (JsonNode contentPart : content) {
      String contentType = contentPart.path("type").asString("");
      ObjectNode fields = NODES.objectNode();
      fields.put("itemType", "message");
      fields.put("contentType", contentType);
      if (first) {
        fields.set("itemFields", itemFields);
        first = false;
      }
      Set<String> semanticContentFields =
          switch (contentType) {
            case "output_text" -> Set.of("type", "text");
            case "refusal" -> Set.of("type", "refusal");
            default -> Set.of("type");
          };
      ObjectNode contentFields = copyFields(contentPart, semanticContentFields);
      if (!contentFields.isEmpty()) {
        fields.set("contentFields", contentFields);
      }
      String text =
          "refusal".equals(contentType)
              ? contentPart.path("refusal").asString("")
              : contentPart.path("text").asString("");
      if ("output_text".equals(contentType)) {
        blocks.add(new ProviderTextBlock(text, responseBlockData(fields)));
      } else if ("refusal".equals(contentType)) {
        fields.put("refusal", true);
        blocks.add(new ProviderTextBlock(text, responseBlockData(fields)));
      } else {
        blocks.add(new ProviderOpaqueBlock("message_content", responseBlockData(fields)));
      }
    }
  }

  private void addReasoningBlocks(List<ProviderContentBlock> blocks, JsonNode item) {
    JsonNode summary = item.path("summary");
    if (summary.isArray() && !summary.isEmpty() && hasOnlyTextSummaryParts(summary)) {
      ObjectNode itemFields = copyFields(item, Set.of("summary"));
      boolean first = true;
      for (JsonNode summaryPart : summary) {
        ObjectNode fields = NODES.objectNode();
        fields.put("itemType", "reasoning");
        fields.put("contentType", "summary_text");
        fields.set("summaryPartFields", copyFields(summaryPart, Set.of("type", "text")));
        if (first) {
          fields.set("itemFields", itemFields);
          first = false;
        }
        blocks.add(
            new ProviderThinkingBlock(
                summaryPart.path("text").stringValue(), responseBlockData(fields)));
      }
      return;
    }
    ObjectNode fields = NODES.objectNode();
    fields.set("item", item.deepCopy());
    blocks.add(new ProviderOpaqueBlock("reasoning", responseBlockData(fields)));
  }

  private static boolean hasOnlyTextSummaryParts(JsonNode summary) {
    for (JsonNode summaryPart : summary) {
      if (!summaryPart.isObject()
          || !"summary_text".equals(summaryPart.path("type").asString(""))
          || !summaryPart.path("text").isString()) {
        return false;
      }
    }
    return true;
  }

  private ProviderBlockData responseBlockData(ObjectNode nativeFields) {
    String itemId = null;
    String encryptedContent = null;
    String itemType = textualValue(nativeFields.remove("itemType"));
    String contentType = textualValue(nativeFields.remove("contentType"));
    JsonNode refusalNode = nativeFields.remove("refusal");
    boolean refusal = refusalNode != null && refusalNode.isBoolean() && refusalNode.booleanValue();
    JsonNode itemFields = nativeFields.get("itemFields");
    if (itemFields instanceof ObjectNode objectFields) {
      itemId = textualValue(objectFields.remove("id"));
      if (itemType == null) {
        itemType = textualValue(objectFields.remove("type"));
      } else {
        objectFields.remove("type");
      }
      encryptedContent = textualValue(objectFields.remove("encrypted_content"));
    }
    JsonNode item = nativeFields.get("item");
    if (item instanceof ObjectNode objectItem) {
      itemId = itemId == null ? textualValue(objectItem.remove("id")) : itemId;
      if (itemType == null) {
        itemType = textualValue(objectItem.remove("type"));
      } else {
        objectItem.remove("type");
      }
      if ("reasoning".equals(itemType)) {
        encryptedContent = textualValue(objectItem.remove("encrypted_content"));
      }
    }
    return new ProviderBlockData(
        ProviderReplayFormat.OPENAI_RESPONSES,
        descriptor.affinity(request.model().modelId()),
        null,
        encryptedContent,
        itemId,
        itemType,
        contentType,
        refusal,
        null,
        nativeFields.has("itemFields") || nativeFields.has("item"),
        nativeFields);
  }

  private static String textualValue(JsonNode node) {
    return node != null && node.isString() ? node.stringValue() : null;
  }

  private ObjectNode copyFields(JsonNode source, Set<String> excludedFields) {
    ObjectNode copy = NODES.objectNode();
    source
        .properties()
        .forEach(
            entry -> {
              if (!excludedFields.contains(entry.getKey())) {
                copy.set(entry.getKey(), entry.getValue().deepCopy());
              }
            });
    return copy;
  }

  /** 权威 item 按顺序聚合；全部无可比文本时保留流式思考，绝不混入草稿。 */
  private void rebuildThinkingFromItems(String streamedThinking) {
    boolean reasoningSeen = false;
    thinkingBuffer.setLength(0);
    for (JsonNode item : rawOutputItems) {
      if ("reasoning".equals(item.path("type").asString(""))) {
        reasoningSeen = true;
        thinkingBuffer.append(
            OpenAiResponsesReasoningText.read(item, ProviderErrorKind.INVALID_RESPONSE));
      }
    }
    if (thinkingBuffer.isEmpty()
        && (reasoningSeen || !explicitTerminalOutputProcessed)
        && !streamedThinking.isBlank()) {
      thinkingBuffer.append(streamedThinking);
    }
  }

  public ProviderResponse response() {
    return finish().response();
  }

  private void appendMessageContent(JsonNode content) {
    if (content == null || content.isNull()) {
      return;
    }
    if (content.isString()) {
      textBuffer.append(content.asString(""));
      return;
    }
    if (!content.isArray()) {
      return;
    }
    for (JsonNode block : content) {
      if (!block.isObject()) {
        continue;
      }
      String type = block.path("type").asString("");
      if ("output_text".equals(type)) {
        textBuffer.append(block.path("text").asString(""));
      } else if ("refusal".equals(type)) {
        textBuffer.append(block.path("refusal").asString(""));
      }
    }
  }

  /**
   * 已知 output item 类型：它们有专门的归一化与 durable 语义校验，不是不透明事实。
   *
   * <p>{@code message}/{@code reasoning}/{@code function_call} 的权威表示来自 delta、done 与 terminal
   * output， 因此不会被 {@code output_item.added} 的早期形态取代；其余官方类型只以不透明事实存在。
   */
  private static boolean isKnownOutputItemType(String itemType) {
    return "message".equals(itemType)
        || "reasoning".equals(itemType)
        || "function_call".equals(itemType);
  }
}

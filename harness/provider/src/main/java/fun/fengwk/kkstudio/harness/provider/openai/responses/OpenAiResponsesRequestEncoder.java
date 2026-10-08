package fun.fengwk.kkstudio.harness.provider.openai.responses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.provider.ProviderProtocolOptionsJson;
import fun.fengwk.kkstudio.harness.provider.RequestBodySizeGuard;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * OpenAI Responses 协议的请求编码器。
 *
 * <p>负责将 {@link ProviderRequest} 规范化编码为 OpenAI {@code /responses} 端点的 UTF-8 JSON 请求体。
 *
 * <p>请求体以 variant 的厂商原生协议选项为 root：非 runtime 所有权字段无损并入，runtime 所有权字段（{@code model}/{@code
 * stream}/{@code store}/{@code instructions}/{@code input}/{@code max_output_tokens} 与 prompt cache
 * 控制字段）由 runtime 唯一决定，冲突一律 {@code INVALID_REQUEST} 且不回显值。
 *
 * <p>assistant replay 直接在 affinity 匹配时原样回放 durable replay payload 的全部 output item（含 reasoning 密文、
 * {@code phase}、native item 的 {@code id}/{@code type}/{@code call_id} 等原生事实）；affinity 失配或 payload 与
 * durable 语义矛盾时才回退语义编码。回退只丢弃 native 事实，绝不因为 payload 存在 opaque item 就拒绝整个请求。payload 的真实形状（顶层字段、已知
 * item 结构、与 durable 文本/思考/工具调用的一致性）始终严格校验，结构损坏一律 {@code INVALID_REQUEST}。
 */
final class OpenAiResponsesRequestEncoder {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /** OpenAI Responses 拒绝低于 16 的 max_output_tokens；该下限只属于本 Provider。 */
  private static final int OPENAI_RESPONSES_MIN_OUTPUT_TOKENS = 16;

  private static final Set<String> ALLOWED_IMAGE_TYPES =
      Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
  private static final String ALLOWED_DOCUMENT_TYPE = "application/pdf";
  private static final Set<String> ALLOWED_PAYLOAD_FIELDS = Set.of("output");

  /** 可等价重建的 reasoning item 成员：超出这些成员的额外成员是 durable 语义无法表达的原生事实。 */
  private static final Set<String> REASONING_ITEM_FIELDS =
      Set.of("type", "id", "status", "summary");

  /** variant 原生选项绝不覆盖的 prompt cache 控制字段：是否下发、下发什么值完全由 runtime 冻结的缓存控制决定。 */
  private static final Set<String> RUNTIME_OWNED_CACHE_FIELDS =
      Set.of("prompt_cache_key", "prompt_cache_retention", "prompt_cache_options");

  /**
   * 与 stateless replay 不变量冲突的有状态字段：{@code previous_response_id}/{@code conversation} 会让服务端自带会话状态，
   * 与本地 {@code store=false} 的 stateless replay 相互矛盾，因此存在即明确拒绝，绝不静默改写或丢弃。
   */
  private static final Set<String> STATEFUL_FIELD_NAMES =
      Set.of("previous_response_id", "conversation");

  private static final Set<String> HOSTED_TOOL_TYPES =
      Set.of(
          "web_search",
          "web_search_preview",
          "file_search",
          "code_interpreter",
          "image_generation",
          "mcp");

  /** runtime 要求 encrypted reasoning 密文随响应返回时使用的 include 条目。 */
  private static final String INCLUDE_REASONING_ENCRYPTED_CONTENT = "reasoning.encrypted_content";

  /** 应用层最终 UTF-8 请求体字节上限守卫；默认使用共享的 192 MiB 应用上限。 */
  private final RequestBodySizeGuard bodySizeGuard;

  OpenAiResponsesRequestEncoder() {
    this(RequestBodySizeGuard.DEFAULT);
  }

  OpenAiResponsesRequestEncoder(RequestBodySizeGuard bodySizeGuard) {
    this.bodySizeGuard = Objects.requireNonNull(bodySizeGuard, "bodySizeGuard");
  }

  OpenAiResponsesEncodedRequest encode(ProviderRequest request, ProviderDescriptor descriptor) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");

    // variant 的厂商原生协议选项是请求体 root：官方字段默认无损并入，runtime 所有权字段单独裁决。
    ObjectNode root = ProviderProtocolOptionsJson.copyOfOptions(request.variant());
    validateNativeOptions(root);
    applyRuntimeFacts(root, request);

    applyOutputBudget(root, request.outputTokens());
    applyReasoningParameters(root, request.variant());

    mergeTools(root, encodeTools(request.tools()));

    ArrayNode inputItems = NODES.arrayNode();
    for (ProviderMessage msg : request.messages()) {
      encodeMessage(msg, descriptor, request.model(), inputItems);
    }
    applyRuntimeOwnedField(root, "input", inputItems);

    applyCacheControl(root, request.cacheControl());

    byte[] utf8Bytes;
    try {
      utf8Bytes = OBJECT_MAPPER.writeValueAsBytes(root);
    } catch (JsonProcessingException e) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "failed to serialize OpenAI Responses request JSON");
    }

    bodySizeGuard.enforce(utf8Bytes);

    return new OpenAiResponsesEncodedRequest(utf8Bytes);
  }

  /**
   * 校验 variant 原生协议选项的顶层结构约束与 runtime 独占字段。
   *
   * <p>{@code tools}/{@code include}/{@code reasoning} 的合并策略依赖各自的官方容器类型，因此声明了非该类型的值即本地失败，
   * 绝不静默改写厂商事实。显式 {@code null} 与缺失不同：它是一次声明，按同样的类型规则判定。
   */
  private static void validateNativeOptions(ObjectNode root) {
    for (String field : STATEFUL_FIELD_NAMES) {
      if (root.has(field)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions conflicts with the stateless replay invariant: " + field);
      }
    }
    for (String field : RUNTIME_OWNED_CACHE_FIELDS) {
      if (root.has(field)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions must not declare the runtime-owned prompt cache field: " + field);
      }
    }
    requireNativeArray(root, "tools");
    if (root.has("background")
        && (!root.get("background").isBoolean() || root.get("background").booleanValue())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "protocolOptions background must be false");
    }
    requireNativeArray(root, "include");
    requireNativeObject(root, "reasoning");
    dedupeInclude(root);
  }

  private static void requireNativeArray(ObjectNode root, String field) {
    JsonNode node = root.get(field);
    if (node != null && !node.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions field must be a JSON array: " + field);
    }
  }

  private static void requireNativeObject(ObjectNode root, String field) {
    JsonNode node = root.get(field);
    if (node != null && !node.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions field must be a JSON object: " + field);
    }
  }

  /**
   * 写入 runtime 唯一所有的顶层事实：{@code model}、{@code stream}、{@code store} 与顶层 {@code instructions}。
   *
   * <p>系统指令是本协议唯一的顶层 {@code instructions} 字符串，绝不作为 input item 下发。
   */
  private static void applyRuntimeFacts(ObjectNode root, ProviderRequest request) {
    applyRuntimeOwnedField(root, "model", NODES.textNode(request.model().modelId()));
    applyRuntimeOwnedField(root, "stream", NODES.booleanNode(true));
    applyRuntimeOwnedField(root, "store", NODES.booleanNode(false));
    applyRuntimeOwnedField(root, "instructions", NODES.textNode(request.systemInstruction()));
  }

  /**
   * runtime 所有权字段：原生选项重复声明同值是无冲突的冗余，冲突值一律 {@code INVALID_REQUEST}；随后 wire 值只由 runtime
   * 事实决定。异常消息只包含字段名，绝不回显原生值。
   */
  private static void applyRuntimeOwnedField(ObjectNode root, String field, JsonNode runtimeValue) {
    JsonNode nativeValue = root.get(field);
    if (nativeValue != null && !nativeValue.equals(runtimeValue)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions conflicts with the runtime-owned request field: " + field);
    }
    root.set(field, runtimeValue);
  }

  /** 输出预算低于本 Provider 的协议下限 {@code 16} 时明确拒绝；合法预算原样编码，绝不擅自扩大冻结值。 */
  private static void applyOutputBudget(ObjectNode root, int outputTokens) {
    if (outputTokens < OPENAI_RESPONSES_MIN_OUTPUT_TOKENS) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "max_output_tokens must be at least 16");
    }
    applyRuntimeOwnedField(root, "max_output_tokens", NODES.numberNode(outputTokens));
  }

  /**
   * reasoning effort 编码：{@code off} 映射为协议关闭值 {@code none}（仅发送 effort，不带 summary/encrypted content），
   * 其他厂商定义值原样下发并请求摘要；null 不发送推理字段，由服务端默认决定。
   *
   * <p>variant 原生 {@code reasoning} object 除 {@code effort}/{@code summary} 外的子字段始终保留：只有原生未声明
   * {@code summary}（缺失或 {@code null}）时才延续 runtime 的 {@code auto} 默认，只有原生未声明 encrypted content
   * 时才追加对应 include。原生 {@code effort} 与 runtime 冲突时明确拒绝且不回显值。
   */
  private static void applyReasoningParameters(ObjectNode root, ModelVariant variant) {
    if (variant == null || variant.reasoningEffort() == null) {
      return;
    }
    ObjectNode reasoning =
        root.has("reasoning") ? (ObjectNode) root.get("reasoning") : root.putObject("reasoning");
    String effort = variant.reasoningOff() ? "none" : variant.reasoningEffort();
    JsonNode nativeEffort = reasoning.get("effort");
    if (nativeEffort != null && !nativeEffort.equals(NODES.textNode(effort))) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions conflicts with the runtime-owned request field: reasoning.effort");
    }
    reasoning.put("effort", effort);
    if (variant.reasoningOff()) {
      return;
    }
    if (!reasoning.hasNonNull("summary")) {
      reasoning.put("summary", "auto");
    }
    ensureInclude(root, INCLUDE_REASONING_ENCRYPTED_CONTENT);
  }

  /** include 去重：保留原生条目首次出现的顺序；重复条目没有额外协议语义，去重不改变厂商声明的集合。 */
  private static void dedupeInclude(ObjectNode root) {
    if (!root.has("include")) {
      return;
    }
    ArrayNode deduped = NODES.arrayNode();
    Set<JsonNode> seen = new LinkedHashSet<>();
    for (JsonNode item : (ArrayNode) root.get("include")) {
      if (seen.add(item)) {
        deduped.add(item);
      }
    }
    root.set("include", deduped);
  }

  /** 只在 runtime 必需条目缺失时追加，绝不覆盖原生 include 的其他条目。 */
  private static void ensureInclude(ObjectNode root, String entry) {
    ArrayNode include =
        root.has("include") ? (ArrayNode) root.get("include") : root.putArray("include");
    for (JsonNode item : include) {
      if (item.isTextual() && entry.equals(item.textValue())) {
        return;
      }
    }
    include.add(entry);
  }

  /** tools 合并：只保留可执行的 hosted 工具，runtime function 工具追加在其后；无任何工具时不发送 {@code tools}。 */
  private static ArrayNode mergeTools(ObjectNode root, ArrayNode runtimeTools) {
    if (!root.has("tools")) {
      if (runtimeTools == null || runtimeTools.isEmpty()) {
        return null;
      }
      root.set("tools", runtimeTools);
      return runtimeTools;
    }
    ArrayNode tools = (ArrayNode) root.get("tools");
    Set<String> names = new HashSet<>();
    for (int i = 0; i < tools.size(); i++) {
      JsonNode tool = tools.get(i);
      JsonNode type = tool.path("type");
      String kind = type.isTextual() ? type.textValue() : "";
      if (!tool.isObject()
          || !HOSTED_TOOL_TYPES.contains(kind)
          || ("mcp".equals(kind)
              && (!tool.path("require_approval").isTextual()
                  || !"never".equals(tool.path("require_approval").textValue())))) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions tools["
                + i
                + "] must be a supported hosted tool; bind client tools through runtime ProviderToolDefinition");
      }
      JsonNode name = tool.get("name");
      if (name != null && name.isTextual() && !names.add(name.textValue())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "protocolOptions tools[" + i + "] duplicates a tool name");
      }
      if (name != null && name.isTextual() && runtimeTools != null) {
        for (JsonNode runtimeTool : runtimeTools) {
          if (name.textValue().equals(runtimeTool.path("name").asText())) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "protocolOptions tools[" + i + "] name conflicts with runtime tool");
          }
        }
      }
    }
    if (runtimeTools != null) {
      tools.addAll(runtimeTools);
    }
    return tools;
  }

  private static ArrayNode encodeTools(List<ProviderToolDefinition> tools) {
    if (tools == null || tools.isEmpty()) {
      return null;
    }
    ArrayNode array = NODES.arrayNode();
    for (ProviderToolDefinition tool : tools) {
      ObjectNode node = array.addObject();
      node.put("type", "function");
      node.put("name", tool.name());
      if (tool.description() != null && !tool.description().isBlank()) {
        node.put("description", tool.description());
      }
      node.set("parameters", encodeStrictParameters(tool));
      node.put("strict", true);
    }
    return array;
  }

  /** 解析工具参数 schema 并归一化为 strict 形态；共享的 inputSchemaJson 绝不被修改。 */
  private static ObjectNode encodeStrictParameters(ProviderToolDefinition tool) {
    JsonNode schemaNode;
    try {
      schemaNode = OBJECT_MAPPER.readTree(tool.inputSchemaJson());
    } catch (JsonProcessingException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "tool inputSchemaJson is not valid JSON");
    }
    if (schemaNode == null || !schemaNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "tool parameters must be a JSON object");
    }
    return OpenAiResponsesStrictSchema.normalize((ObjectNode) schemaNode);
  }

  private static void encodeMessage(
      ProviderMessage msg,
      ProviderDescriptor descriptor,
      ModelDescriptor model,
      ArrayNode inputItems) {
    Objects.requireNonNull(msg, "msg");
    ProviderMessageRole role = msg.role();

    switch (role) {
      case USER -> {
        ObjectNode userMsg = inputItems.addObject();
        userMsg.put("type", "message");
        userMsg.put("role", "user");
        ArrayNode contents = userMsg.putArray("content");
        for (ProviderContentBlock block : msg.contents()) {
          ObjectNode blockNode = encodeUserContentBlock(block);
          contents.add(blockNode);
        }
      }
      case ASSISTANT -> {
        ProviderReplayState replayState = msg.replayState();
        if (replayState != null
            && canReplay(replayState, descriptor, model.modelId(), msg.contents())) {
          ArrayNode outputArray = extractOutputArray(replayState.payload());
          for (JsonNode item : outputArray) {
            inputItems.add(item.deepCopy());
          }
          return;
        }

        // Semantic fallback
        encodeAssistantSemanticFallback(msg.contents(), inputItems);
      }
      case TOOL -> {
        for (ProviderContentBlock block : msg.contents()) {
          if (block instanceof ProviderToolResultBlock resultBlock) {
            ObjectNode toolOutput = inputItems.addObject();
            toolOutput.put("type", "function_call_output");
            toolOutput.put("call_id", resultBlock.toolCallId());
            encodeToolResultContent(resultBlock, toolOutput);
          } else {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "TOOL message must only contain ProviderToolResultBlock");
          }
        }
      }
    }
  }

  private static ObjectNode encodeUserContentBlock(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "input_text");
      node.put("text", textBlock.text());
      return node;
    }
    if (block instanceof ProviderImageBlock imageBlock) {
      validateImageType(imageBlock.mediaType());
      validateUri(imageBlock.source());
      ObjectNode node = NODES.objectNode();
      node.put("type", "input_image");
      node.put("image_url", imageBlock.source());
      node.put("detail", "auto");
      return node;
    }
    if (block instanceof ProviderDocumentBlock docBlock) {
      validateDocumentType(docBlock.mediaType());
      validateUri(docBlock.source());
      ObjectNode node = NODES.objectNode();
      node.put("type", "input_file");
      if (docBlock.source().startsWith("data:")) {
        node.put("file_data", docBlock.source());
        node.put("filename", "document.pdf");
      } else {
        node.put("file_url", docBlock.source());
      }
      return node;
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "OpenAI Responses does not support content block type: "
            + block.getClass().getSimpleName());
  }

  private static void encodeAssistantSemanticFallback(
      List<ProviderContentBlock> contents, ArrayNode inputItems) {
    StringBuilder textBuf = new StringBuilder();
    StringBuilder thinkBuf = new StringBuilder();
    List<ProviderToolCallBlock> toolCalls = new ArrayList<>();

    for (ProviderContentBlock block : contents) {
      if (block instanceof ProviderTextBlock textBlock) {
        textBuf.append(textBlock.text());
      } else if (block instanceof ProviderThinkingBlock thinkingBlock) {
        thinkBuf.append(thinkingBlock.thinking());
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        toolCalls.add(toolCallBlock);
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "Unsupported assistant content block: " + block.getClass().getSimpleName());
      }
    }

    if (!thinkBuf.isEmpty()) {
      ObjectNode reasoning = inputItems.addObject();
      reasoning.put("type", "reasoning");
      ArrayNode summary = reasoning.putArray("summary");
      ObjectNode summaryItem = summary.addObject();
      summaryItem.put("type", "summary_text");
      summaryItem.put("text", thinkBuf.toString());
    }

    if (!textBuf.isEmpty()) {
      ObjectNode msg = inputItems.addObject();
      msg.put("type", "message");
      msg.put("role", "assistant");
      ArrayNode contentArr = msg.putArray("content");
      ObjectNode contentBlock = contentArr.addObject();
      contentBlock.put("type", "output_text");
      contentBlock.put("text", textBuf.toString());
    }

    for (ProviderToolCallBlock call : toolCalls) {
      ProviderToolCall tc = call.toolCall();
      parseJsonObject(tc.argumentsJson(), "toolCall argumentsJson must be a JSON object");
      ObjectNode fc = inputItems.addObject();
      fc.put("type", "function_call");
      fc.put("call_id", tc.id());
      fc.put("name", tc.name());
      fc.put("arguments", tc.argumentsJson());
    }
  }

  /**
   * 工具结果编码：单文本/JSON 保持简洁的 string 形态，含媒体时使用 {@code output} 数组。
   *
   * <p>数组元素只允许 {@code input_text}、{@code input_image} 与 {@code input_file}（PDF）：OpenAI Responses 的
   * {@code function_call_output.output} 明确支持这三种输入项。音频、视频与任何其他块类型都以 {@code INVALID_REQUEST} 明确失败，
   * 绝不静默丢弃媒体。
   */
  private static void encodeToolResultContent(
      ProviderToolResultBlock resultBlock, ObjectNode toolOutput) {
    List<ProviderContentBlock> contents = resultBlock.contents();
    if (contents == null || contents.isEmpty()) {
      toolOutput.put("output", "");
      return;
    }
    if (contents.size() == 1) {
      ProviderContentBlock single = contents.get(0);
      if (single instanceof ProviderTextBlock textBlock) {
        toolOutput.put("output", textBlock.text());
        return;
      }
      if (single instanceof ProviderJsonBlock jsonBlock) {
        toolOutput.put("output", jsonBlock.json());
        return;
      }
    }

    ArrayNode array = toolOutput.putArray("output");
    for (ProviderContentBlock block : contents) {
      array.add(encodeToolResultItem(block));
    }
  }

  /** 工具结果的文本与 JSON 统一编码为 {@code input_text}，媒体与用户内容共用同一规则，保证同模态两处 wire 形态一致。 */
  private static ObjectNode encodeToolResultItem(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      ObjectNode item = NODES.objectNode();
      item.put("type", "input_text");
      item.put("text", textBlock.text());
      return item;
    }
    if (block instanceof ProviderJsonBlock jsonBlock) {
      ObjectNode item = NODES.objectNode();
      item.put("type", "input_text");
      item.put("text", jsonBlock.json());
      return item;
    }
    if (block instanceof ProviderImageBlock || block instanceof ProviderDocumentBlock) {
      return encodeUserContentBlock(block);
    }
    throw new ProviderException(
        ProviderErrorKind.INVALID_REQUEST,
        "OpenAI Responses does not support tool result content block type: "
            + block.getClass().getSimpleName());
  }

  /**
   * 判断 durable replay 是否可在本轮原位回放。
   *
   * <p>先严格校验 payload 与 durable 语义的一致性（结构损坏或与 durable 文本/思考/工具调用矛盾在此抛出 {@code INVALID_REQUEST}，与
   * affinity 是否匹配无关）；随后 affinity 匹配时原位回放 payload 的全部 output item，否则回退语义编码。 opaque / native item
   * 本身从不导致拒绝：affinity 匹配即原样回放，失配则随语义回退被丢弃。
   */
  private static boolean canReplay(
      ProviderReplayState replayState,
      ProviderDescriptor descriptor,
      String requestedModel,
      List<ProviderContentBlock> durableContents) {
    if (replayState == null) {
      return false;
    }
    if (replayState.format() != ProviderReplayFormat.OPENAI_RESPONSES) {
      return false;
    }

    ArrayNode outputArray = extractOutputArray(replayState.payload());
    if (declinesReplay(outputArray, durableContents)) {
      // 只由空 reasoning 占位符构成：不承载原生推理，改由语义编码承载 durable 思考。
      return false;
    }

    return replayState.affinity().equals(descriptor.affinity(requestedModel));
  }

  private static ArrayNode extractOutputArray(JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid replay payload: must be a JSON object");
    }
    validateAllowedFields(payload, ALLOWED_PAYLOAD_FIELDS, "replay payload");
    JsonNode outputNode = payload.get("output");
    if (outputNode == null || !outputNode.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "invalid replay payload: missing or non-array 'output' field");
    }
    return (ArrayNode) outputNode;
  }

  /**
   * 严格校验 durable replay payload 的真实形状与 durable 语义一致性，并返回该 payload 是否只由空 reasoning 占位符构成。
   *
   * <p>结构性损坏或与 durable 文本/思考/工具调用矛盾都在此抛出 {@code INVALID_REQUEST}，与 affinity 是否匹配无关。已知 {@code
   * message}/{@code reasoning}/{@code function_call} 只对 durable 语义所必需的字段做结构/类型校验并逐项比对 durable 内容；超出
   * durable 语义的官方响应字段（{@code phase}、{@code annotations}/{@code logprobs}、reasoning 密文、 {@code
   * status} 及未来新增成员）与未知官方 item 都作为原生事实原样保留，不影响校验结果。
   *
   * <p>无 {@code encrypted_content}、无可用摘要文本、也没有超出 {@code type/id/status/summary} 成员的 reasoning item
   * 是上游“原生推理不可用”的合法空占位符：当 payload 只由这类占位符构成时不承载原生推理，返回 {@code true} 由调用方回退语义编码， 而不是判为损坏请求。
   */
  private static boolean declinesReplay(
      ArrayNode outputArray, List<ProviderContentBlock> durableContents) {
    StringBuilder replayText = new StringBuilder();
    StringBuilder replayThinking = new StringBuilder();
    List<ProviderToolCall> replayToolCalls = new ArrayList<>();
    int reasoningItemCount = 0;
    int emptyReasoningPlaceholderCount = 0;

    for (JsonNode item : outputArray) {
      if (!item.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid replay output item: must be a JSON object");
      }
      if (!item.has("type")
          || !item.get("type").isTextual()
          || item.get("type").textValue().isBlank()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "invalid replay output item: missing string type");
      }
      String type = item.get("type").textValue();
      switch (type) {
        case "message" -> {
          if (!item.has("role")
              || !item.get("role").isTextual()
              || !"assistant".equals(item.get("role").textValue())) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "replay message item must have role='assistant'");
          }
          // id/status/phase 与任何额外成员都是原生事实，原样保留
          if (item.has("id")) {
            if (!item.get("id").isTextual() || item.get("id").textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "replay message id must be non-blank string");
            }
          }
          if (item.has("phase")) {
            if (!item.get("phase").isTextual() || item.get("phase").textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay message phase must be non-blank string");
            }
          }
          JsonNode content = item.get("content");
          if (content == null || !content.isArray()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "replay message content must be an array");
          }
          for (JsonNode block : content) {
            if (!block.isObject()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay message content block must be an object");
            }
            if (!block.has("type") || !block.get("type").isTextual()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay message content block must have string type");
            }
            switch (block.get("type").textValue()) {
              case "output_text" -> {
                if (!block.has("text") || !block.get("text").isTextual()) {
                  throw new ProviderException(
                      ProviderErrorKind.INVALID_REQUEST,
                      "replay output_text block must have string text");
                }
                // annotations/logprobs 与任何额外成员都是原生事实，原样保留
                replayText.append(block.get("text").textValue());
              }
              case "refusal" -> {
                if (!block.has("refusal") || !block.get("refusal").isTextual()) {
                  throw new ProviderException(
                      ProviderErrorKind.INVALID_REQUEST,
                      "replay refusal block must have string refusal");
                }
                replayText.append(block.get("refusal").textValue());
              }
              default -> throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay message content block type must be 'output_text' or 'refusal'");
            }
          }
        }
        case "reasoning" -> {
          if (item.has("id")) {
            if (!item.get("id").isTextual() || item.get("id").textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay reasoning id must be non-blank string");
            }
          }
          boolean itemHasEncrypted = false;
          if (item.has("encrypted_content")) {
            JsonNode encNode = item.get("encrypted_content");
            if (!encNode.isTextual() || encNode.textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "replay reasoning encrypted_content must be non-blank string");
            }
            itemHasEncrypted = true;
          }
          boolean itemHasUsableSummary = false;
          if (item.has("summary")) {
            JsonNode summary = item.get("summary");
            if (!summary.isArray()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "replay reasoning summary must be an array");
            }
            for (JsonNode s : summary) {
              if (!s.isObject()) {
                throw new ProviderException(
                    ProviderErrorKind.INVALID_REQUEST,
                    "replay reasoning summary block must be an object");
              }
              if (!s.has("type")
                  || !s.get("type").isTextual()
                  || !"summary_text".equals(s.get("type").textValue())) {
                throw new ProviderException(
                    ProviderErrorKind.INVALID_REQUEST,
                    "replay reasoning summary block type must be 'summary_text'");
              }
              if (!s.has("text") || !s.get("text").isTextual()) {
                throw new ProviderException(
                    ProviderErrorKind.INVALID_REQUEST,
                    "replay reasoning summary block must have string text");
              }
              // opaque summary 块（除 type/text 外还有成员）仍按原生事实原样保留
              String summaryText = s.get("text").textValue();
              if (!summaryText.isBlank()) {
                itemHasUsableSummary = true;
              }
            }
          }
          // content 形状严格校验与可比文本读取复用同一 helper；损坏历史一律 INVALID_REQUEST。
          replayThinking.append(
              OpenAiResponsesReasoningText.read(item, ProviderErrorKind.INVALID_REQUEST));
          reasoningItemCount++;
          if (!itemHasEncrypted
              && !hasFieldOutside(item, REASONING_ITEM_FIELDS)
              && !itemHasUsableSummary) {
            // 无密文、无可用摘要文本、无额外成员：结构合法的空占位符（上游声明原生推理不可用），而不是损坏请求。
            emptyReasoningPlaceholderCount++;
          }
        }
        case "function_call" -> {
          String callId = null;
          if (item.has("call_id")) {
            if (!item.get("call_id").isTextual() || item.get("call_id").textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  "function_call call_id must be non-blank string");
            }
            callId = item.get("call_id").textValue();
          }
          if (item.has("id")) {
            if (!item.get("id").isTextual() || item.get("id").textValue().isBlank()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "function_call id must be non-blank string");
            }
            if (callId == null) {
              callId = item.get("id").textValue();
            }
          }
          if (callId == null || callId.isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "function_call missing call_id or id");
          }
          if (!item.has("name")
              || !item.get("name").isTextual()
              || item.get("name").textValue().isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "function_call missing non-blank string name");
          }
          String name = item.get("name").textValue();
          if (!item.has("arguments") || !item.get("arguments").isTextual()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "function_call missing string arguments");
          }
          String args = item.get("arguments").textValue();
          parseJsonObject(args, "function_call arguments must be a JSON object");
          replayToolCalls.add(new ProviderToolCall(callId, name, args));
        }
        default -> {
          // 未知但可信的官方 item（web/file search、code interpreter、image generation、custom tool
          // call、computer/shell 等）：不承载 durable 语义，因此不参与文本/思考/工具一致性比对，只作为原生事实原样回放。
        }
      }
    }

    StringBuilder durableText = new StringBuilder();
    StringBuilder durableThinking = new StringBuilder();
    List<ProviderToolCall> durableToolCalls = new ArrayList<>();

    for (ProviderContentBlock block : durableContents) {
      if (block instanceof ProviderTextBlock tb) {
        durableText.append(tb.text());
      } else if (block instanceof ProviderThinkingBlock thb) {
        durableThinking.append(thb.thinking());
      } else if (block instanceof ProviderToolCallBlock tcb) {
        ProviderToolCall tc = tcb.toolCall();
        parseJsonObject(tc.argumentsJson(), "durable tool call arguments must be a JSON object");
        durableToolCalls.add(tc);
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "durable content block not supported in assistant message replay: "
                + block.getClass().getSimpleName());
      }
    }

    if (!replayText.toString().equals(durableText.toString())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay text content mismatch with durable message content");
    }
    // 只由空 reasoning 占位符构成：至少一个 reasoning，且每个都无密文、无任何非空摘要文本、无额外成员。
    boolean replayIsEmptyPlaceholderOnly =
        reasoningItemCount > 0 && emptyReasoningPlaceholderCount == reasoningItemCount;
    if (!replayIsEmptyPlaceholderOnly) {
      if (!durableThinking.isEmpty()
          && !replayThinking.toString().equals(durableThinking.toString())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay thinking content mismatch with durable message thinking");
      } else if (durableThinking.isEmpty() && !replayThinking.isEmpty()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay thinking content mismatch with durable message thinking");
      }
    }
    if (replayToolCalls.size() != durableToolCalls.size()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "replay tool calls count mismatch with durable tool calls");
    }
    for (int i = 0; i < replayToolCalls.size(); i++) {
      ProviderToolCall rc = replayToolCalls.get(i);
      ProviderToolCall dc = durableToolCalls.get(i);
      if (!rc.id().equals(dc.id())
          || !rc.name().equals(dc.name())
          || !rc.argumentsJson().equals(dc.argumentsJson())) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "replay tool call mismatch with durable tool call at index " + i);
      }
    }

    return replayIsEmptyPlaceholderOnly;
  }

  /** 节点是否携带给定白名单之外的成员：reasoning 额外成员都是 durable 语义无法表达的原生事实。 */
  private static boolean hasFieldOutside(JsonNode node, Set<String> allowedFields) {
    Iterator<String> fields = node.fieldNames();
    while (fields.hasNext()) {
      if (!allowedFields.contains(fields.next())) {
        return true;
      }
    }
    return false;
  }

  private static void validateAllowedFields(
      JsonNode node, Set<String> allowedFields, String context) {
    Iterator<String> fields = node.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!allowedFields.contains(field)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "disallowed field '" + field + "' in " + context);
      }
    }
  }

  /**
   * 将 runtime 冻结的缓存控制映射为本协议 wire 字段：{@code retention = NONE} 不下发任何 cache hint；否则以会话 UUID 原样作为
   * {@code prompt_cache_key}，并把留存档位映射为原有支持的 {@code prompt_cache_retention} 值。
   */
  private static void applyCacheControl(ObjectNode root, ProviderCacheControl cacheControl) {
    if (cacheControl.retention() == PromptCacheRetention.NONE) {
      return;
    }
    root.put("prompt_cache_key", cacheControl.key());
    root.put(
        "prompt_cache_retention",
        cacheControl.retention() == PromptCacheRetention.SHORT ? "in_memory" : "24h");
  }

  private static void validateImageType(String mediaType) {
    if (mediaType == null || !ALLOWED_IMAGE_TYPES.contains(mediaType.toLowerCase(Locale.ROOT))) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Unsupported image media type: "
              + mediaType
              + ". Supported types: "
              + ALLOWED_IMAGE_TYPES);
    }
  }

  private static void validateDocumentType(String mediaType) {
    if (mediaType == null || !ALLOWED_DOCUMENT_TYPE.equalsIgnoreCase(mediaType)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "Unsupported document media type: " + mediaType + ". Only application/pdf is supported");
    }
  }

  private static void validateUri(String source) {
    if (source == null || source.isBlank()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "media source must not be blank");
    }
    if (source.startsWith("data:")) {
      int comma = source.indexOf(',');
      if (comma < 0) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, "invalid data URI format");
      }
      return;
    }
    try {
      URI uri = URI.create(source);
      String scheme = uri.getScheme();
      if (scheme == null
          || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "media source URL must use http or https scheme");
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "media source URL must contain a valid host");
      }
    } catch (IllegalArgumentException e) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "media source is not a valid URI");
    }
  }

  private static JsonNode parseJsonObject(String json, String errorMessage) {
    if (json == null || json.isBlank()) {
      throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
    }
    try (JsonParser parser = OBJECT_MAPPER.createParser(json)) {
      JsonNode node = OBJECT_MAPPER.readTree(parser);
      if (node == null || !node.isObject() || parser.nextToken() != null) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
      }
      return node;
    } catch (Exception e) {
      throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorMessage);
    }
  }
}

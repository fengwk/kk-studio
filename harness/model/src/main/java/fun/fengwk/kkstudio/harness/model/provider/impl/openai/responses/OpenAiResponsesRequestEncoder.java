package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.impl.RequestBodySizeGuard;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
public final class OpenAiResponsesRequestEncoder {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** OpenAI Responses 拒绝低于 16 的 max_output_tokens；该下限只属于本 Provider。 */
  private static final int MIN_OUTPUT_TOKENS = 16;

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

  public OpenAiResponsesRequestEncoder() {
    this(RequestBodySizeGuard.DEFAULT);
  }

  OpenAiResponsesRequestEncoder(RequestBodySizeGuard bodySizeGuard) {
    this.bodySizeGuard = Objects.requireNonNull(bodySizeGuard, "bodySizeGuard");
  }

  public OpenAiResponsesEncodedRequest encode(
      ProviderRequest request, ProviderDescriptor descriptor) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(descriptor, "descriptor");

    // variant 的厂商原生协议选项是请求体 root：官方字段默认无损并入，runtime 所有权字段单独裁决。
    ObjectNode root = request.variant().protocolOptions().copyOptions();
    validateNativeOptions(root);
    applyRuntimeFacts(root, request);
    applyOutputBudget(root, request.outputTokens());
    applyReasoningParameters(root, request.variant());
    mergeTools(root, encodeTools(request.tools()));
    applyRuntimeOwnedField(
        root,
        "input",
        OpenAiResponsesInputEncoder.encode(request.messages(), descriptor, request.model()));
    applyCacheControl(root, request.cacheControl());

    byte[] utf8Bytes = serialize(root);
    bodySizeGuard.enforce(utf8Bytes);
    return new OpenAiResponsesEncodedRequest(utf8Bytes);
  }

  private static byte[] serialize(ObjectNode root) {
    try {
      return OBJECT_MAPPER.writeValueAsBytes(root);
    } catch (JacksonException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "failed to serialize OpenAI Responses request JSON");
    }
  }

  /**
   * 校验 variant 原生协议选项的顶层结构约束与 runtime 独占字段。
   *
   * <p>{@code tools}/{@code include}/{@code reasoning} 的合并策略依赖各自的官方容器类型，因此声明了非该类型的值即本地失败，
   * 绝不静默改写厂商事实。显式 {@code null} 与缺失不同：它是一次声明，按同样的类型规则判定。
   */
  private static void validateNativeOptions(ObjectNode root) {
    rejectDeclaredFields(
        root,
        STATEFUL_FIELD_NAMES,
        "protocolOptions conflicts with the stateless replay invariant: ");
    rejectDeclaredFields(
        root,
        RUNTIME_OWNED_CACHE_FIELDS,
        "protocolOptions must not declare the runtime-owned prompt cache field: ");
    requireNativeArray(root, "tools");
    requireDisabledBackground(root);
    requireNativeArray(root, "include");
    requireNativeObject(root, "reasoning");
    dedupeInclude(root);
  }

  private static void rejectDeclaredFields(
      ObjectNode root, Set<String> fields, String errorPrefix) {
    for (String field : fields) {
      if (root.has(field)) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, errorPrefix + field);
      }
    }
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

  private static void requireDisabledBackground(ObjectNode root) {
    if (root.has("background")
        && (!root.get("background").isBoolean() || root.get("background").booleanValue())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "protocolOptions background must be false");
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
    if (outputTokens < MIN_OUTPUT_TOKENS) {
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
      if (item.isString() && entry.equals(item.stringValue())) {
        return;
      }
    }
    include.add(entry);
  }

  /** tools 合并：只保留可执行的 hosted 工具，runtime function 工具追加在其后；无任何工具时不发送 {@code tools}。 */
  private static void mergeTools(ObjectNode root, ArrayNode runtimeTools) {
    if (!root.has("tools")) {
      if (runtimeTools != null && !runtimeTools.isEmpty()) {
        root.set("tools", runtimeTools);
      }
      return;
    }
    ArrayNode tools = (ArrayNode) root.get("tools");
    Set<String> hostedNames = new HashSet<>();
    for (int i = 0; i < tools.size(); i++) {
      validateHostedTool(tools.get(i), i, runtimeTools, hostedNames);
    }
    if (runtimeTools != null) {
      tools.addAll(runtimeTools);
    }
  }

  private static void validateHostedTool(
      JsonNode tool, int index, ArrayNode runtimeTools, Set<String> hostedNames) {
    String kind = tool.path("type").isString() ? tool.path("type").stringValue() : "";
    if (!tool.isObject()
        || !HOSTED_TOOL_TYPES.contains(kind)
        || ("mcp".equals(kind) && !requiresNoApproval(tool))) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions tools["
              + index
              + "] must be a supported hosted tool; bind client tools through runtime"
              + " ProviderToolDefinition");
    }
    JsonNode name = tool.get("name");
    if (name == null || !name.isString()) {
      return;
    }
    if (!hostedNames.add(name.stringValue())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions tools[" + index + "] duplicates a tool name");
    }
    if (runtimeTools != null && containsToolName(runtimeTools, name.stringValue())) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "protocolOptions tools[" + index + "] name conflicts with runtime tool");
    }
  }

  private static boolean requiresNoApproval(JsonNode tool) {
    return tool.path("require_approval").isString()
        && "never".equals(tool.path("require_approval").stringValue());
  }

  private static boolean containsToolName(ArrayNode runtimeTools, String name) {
    for (JsonNode runtimeTool : runtimeTools) {
      if (name.equals(runtimeTool.path("name").asString(""))) {
        return true;
      }
    }
    return false;
  }

  private static ArrayNode encodeTools(List<ProviderToolDefinition> tools) {
    if (tools == null || tools.isEmpty()) {
      return null;
    }
    ArrayNode array = NODES.arrayNode();
    for (ProviderToolDefinition tool : tools) {
      array.add(encodeTool(tool));
    }
    return array;
  }

  private static ObjectNode encodeTool(ProviderToolDefinition tool) {
    ObjectNode node = NODES.objectNode();
    node.put("type", "function");
    node.put("name", tool.name());
    if (tool.description() != null && !tool.description().isBlank()) {
      node.put("description", tool.description());
    }
    node.set("parameters", encodeStrictParameters(tool));
    node.put("strict", true);
    return node;
  }

  /** 解析工具参数 schema 并归一化为 strict 形态；共享的 inputSchemaJson 绝不被修改。 */
  private static ObjectNode encodeStrictParameters(ProviderToolDefinition tool) {
    JsonNode schemaNode;
    try {
      schemaNode = OBJECT_MAPPER.readTree(tool.inputSchemaJson());
    } catch (JacksonException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "tool inputSchemaJson is not valid JSON");
    }
    if (schemaNode == null || !schemaNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "tool parameters must be a JSON object");
    }
    return OpenAiResponsesStrictSchema.normalize((ObjectNode) schemaNode);
  }

  /**
   * 将 runtime 冻结的缓存控制映射为本协议 wire 字段：{@code retention = NONE} 不下发任何 cache hint；否则以会话 UUID 原样作为
   * {@code prompt_cache_key}，并把留存档位映射为 {@code prompt_cache_retention}。
   */
  private static void applyCacheControl(ObjectNode root, ProviderCacheControl cacheControl) {
    switch (cacheControl.retention()) {
      case NONE -> {}
      case SHORT -> applyPromptCache(root, cacheControl.key(), "in_memory");
      case LONG -> applyPromptCache(root, cacheControl.key(), "24h");
    }
  }

  private static void applyPromptCache(ObjectNode root, String key, String retention) {
    root.put("prompt_cache_key", key);
    root.put("prompt_cache_retention", retention);
  }
}

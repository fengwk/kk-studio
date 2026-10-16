package fun.fengwk.kkstudio.web.upgrade;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 离线升级的两阶段纯转换：先严格解析旧七节配置，再以六节 settings 装配的实际安装目录投影 Plugin 普通值。
 *
 * <p>旧字段形状与标量类型必须匹配；nullable 文本可以省略或显式 null，符合旧 Codec 的 NON_NULL 存储语义。 六节聚合仅经当前 {@link
 * SystemSettingsCodec} 解码，不复制领域模型。
 *
 * <p>无消费者的旧 comfyui 块仅允许默认值退休；实际 H3 只取 minimaxH3 的端点、预算与开关。 新工作流来自实际 Plugin 默认，仅用于未来配置，不补历史
 * binding；启用但缺端点、Agent 或显式凭据必须拒绝。
 *
 * <p>输出经实际 PluginSchema 校验，完整业务校验仍须由 {@link OfflinePluginCatalog#prepareInitial} 真正 configure 完成。
 * 本类不执行宿主端口、不读取秘密或数据库，也不清洗或截断值；失败不回显输入或附带原始 cause。
 */
final class LegacySystemSettings {

  static final String MALFORMED = "legacy system settings is not valid strict JSON";
  static final String SECTIONS =
      "legacy system settings must carry the exact seven legacy sections";
  static final String INTEGRATIONS_OBJECT =
      "legacy system settings integrations must be a JSON object";
  static final String INTEGRATIONS_BLOCKS =
      "legacy integrations must carry the exact five legacy blocks";
  static final String COMFYUI_BLOCK =
      "legacy integrations.comfyui does not match the legacy schema";
  static final String OPENCLI_HUB_BLOCK =
      "legacy integrations.openCliHub does not match the legacy schema";
  static final String SEEDANCE_BLOCK =
      "legacy integrations.seedance does not match the legacy schema";
  static final String GPT_IMAGE_2_BLOCK =
      "legacy integrations.gptImage2 does not match the legacy schema";
  static final String MINIMAX_H3_BLOCK =
      "legacy integrations.minimaxH3 does not match the legacy schema";
  static final String SIX_SECTIONS_REJECTED =
      "legacy system settings are rejected by the current settings codec";
  static final String COMFYUI_BLOCK_NOT_DEFAULT =
      "legacy integrations.comfyui is not the exact retired default";
  static final String PLUGIN_PROFILE =
      "legacy upgrade requires exactly the frozen comfyui/media/minimax-mavis/opencli profile";
  static final String COMFYUI_BEARER_REQUIRED =
      "enabled legacy minimaxH3 requires an explicit ComfyUI bearer credential";
  static final String OPENCLI_API_KEY_REQUIRED =
      "enabled legacy openCliHub requires an explicit OpenCLI API key";

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          // 自由 JSON number 以 BigDecimal 读回，避免 1e309 -> Infinity、1e-324 -> 0.0 的精度/类型失真。
          .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private static final SystemSettingsCodec SETTINGS_CODEC = new SystemSettingsCodec();

  private static final Set<String> LEGACY_SECTIONS =
      Set.of(
          "tool",
          "aiRuntime",
          "environment",
          "network",
          "integrations",
          "storageMedia",
          "advanced");

  private static final Set<String> LEGACY_INTEGRATION_BLOCKS =
      Set.of("comfyui", "openCliHub", "seedance", "gptImage2", "minimaxH3");

  private static final Set<String> LEGACY_COMFYUI_FIELDS =
      Set.of(
          "enabled",
          "baseUrl",
          "connectTimeoutMillis",
          "readTimeoutMillis",
          "websocketTimeoutMillis",
          "maxInputFileBytes");

  private static final Set<String> LEGACY_OPENCLI_HUB_FIELDS =
      Set.of(
          "enabled",
          "baseUrl",
          "connectTimeoutMillis",
          "requestTimeoutMillis",
          "longPollTimeoutMillis",
          "streamBufferBytes",
          "maxJsonResponseBytes",
          "maxErrorResponseBytes",
          "maxOutputChars");

  private static final Set<String> LEGACY_SEEDANCE_FIELDS =
      Set.of(
          "enabled",
          "workspaceId",
          "retry",
          "hubExecutionTimeoutMillis",
          "statusPollIntervalMillis",
          "maxWaitMillis");

  private static final Set<String> LEGACY_GPT_IMAGE_2_FIELDS =
      Set.of("paidEnabled", "askTimeoutSeconds", "hubExecutionTimeoutMillis", "maxWaitMillis");

  private static final Set<String> LEGACY_MINIMAX_H3_FIELDS =
      Set.of(
          "enabled",
          "promptAgentName",
          "promptMaxWaitMillis",
          "comfyBaseUrl",
          "comfyConnectTimeoutMillis",
          "comfyRequestTimeoutMillis",
          "comfyPollIntervalMillis",
          "comfyMaxWaitMillis");

  /** 冻结的旧官方 classpath 贡献语义：media / minimax-mavis 安装即贡献。 */
  private static final String FIXED_ENABLED_VALUES = "{\"enabled\":true}";

  /** 本转换 profile 的冻结安装集合：缺项或多出任何 id 都 fail-closed（不新增插件安装 authority）。 */
  private static final Set<String> FROZEN_PLUGIN_IDS =
      Set.of("comfyui", "media", "minimax-mavis", "opencli");

  // 旧 integrations.comfyui 的无消费者 DEFAULT：只有整块逐字段等于它才可退休。
  private static final long LEGACY_COMFYUI_CONNECT_TIMEOUT_MILLIS = 10_000L;
  private static final long LEGACY_COMFYUI_READ_TIMEOUT_MILLIS = 30_000L;
  private static final long LEGACY_COMFYUI_WEBSOCKET_TIMEOUT_MILLIS = 1_800_000L;
  private static final long LEGACY_COMFYUI_MAX_INPUT_FILE_BYTES = 50L * 1024 * 1024;

  private final SystemSettings settings;

  private final ObjectNode integrations;

  private LegacySystemSettings(SystemSettings settings, ObjectNode integrations) {
    this.settings = settings;
    this.integrations = integrations;
  }

  /**
   * 解析并校验一次完整的旧七节 settings JSON。
   *
   * @throws IllegalArgumentException JSON 非法、节 / 块 / 字段 / 类型不符、六节被当前 codec 拒绝或死块非默认时（只带固定安全消息）
   */
  static LegacySystemSettings parse(String legacyJson) {
    ObjectNode root = parseObject(legacyJson);
    requireExactFields(root, LEGACY_SECTIONS, SECTIONS);
    ObjectNode integrations = requireBlock(root, "integrations", INTEGRATIONS_OBJECT);
    requireExactFields(integrations, LEGACY_INTEGRATION_BLOCKS, INTEGRATIONS_BLOCKS);

    requireComfyui(requireBlock(integrations, "comfyui", COMFYUI_BLOCK));
    requireOpenCliHub(requireBlock(integrations, "openCliHub", OPENCLI_HUB_BLOCK));
    requireSeedance(requireBlock(integrations, "seedance", SEEDANCE_BLOCK));
    requireGptImage2(requireBlock(integrations, "gptImage2", GPT_IMAGE_2_BLOCK));
    requireMiniMaxH3(requireBlock(integrations, "minimaxH3", MINIMAX_H3_BLOCK));

    return new LegacySystemSettings(decodeCurrentSections(root), integrations);
  }

  /** 当前的不可变六节聚合（已由当前 codec 严格解码校验）。 */
  SystemSettings settings() {
    return settings;
  }

  /**
   * 把旧 integrations 的普通值投影为恰好覆盖 {@code registry} 的每个 id 的配置 JSON（稳定 id 顺序、不可变）。
   *
   * @param registry 以当前六节 settings 现场打开的真实安装目录，是唯一安装 authority
   * @param hasComfyBearer 操作人是否已显式物化 comfyui 的 bearer 秘密材料（不传值）
   * @param hasOpenCliApiKey 操作人是否已显式物化 opencli 的 apiKey 秘密材料（不传值，旧数据本就没有该 Key）
   * @throws IllegalArgumentException 安装集合非冻结 profile、启用但缺显式材料，或普通值不符目标 PluginSchema 时（只带固定安全消息）
   */
  Map<String, String> pluginValues(
      StudioPluginRegistry registry, boolean hasComfyBearer, boolean hasOpenCliApiKey) {
    Objects.requireNonNull(registry, "registry");
    if (!new LinkedHashSet<>(registry.pluginIds()).equals(FROZEN_PLUGIN_IDS)) {
      throw new IllegalArgumentException(PLUGIN_PROFILE);
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      String pluginId = plugin.descriptor().pluginId();
      if ("comfyui".equals(pluginId)) {
        values.put(pluginId, comfyuiValues(plugin.configurationSchema(), hasComfyBearer));
      } else if ("opencli".equals(pluginId)) {
        values.put(pluginId, openCliValues(plugin.configurationSchema(), hasOpenCliApiKey));
      } else {
        values.put(pluginId, fixedEnabledValues(plugin.configurationSchema()));
      }
    }
    return Collections.unmodifiableMap(values);
  }

  /** media / minimax-mavis 保持旧官方 classpath 贡献语义：安装即 {@code {"enabled":true}}。 */
  private static String fixedEnabledValues(PluginConfigSchema schema) {
    return schema.resolveValues(FIXED_ENABLED_VALUES);
  }

  /**
   * comfyui：只取旧 minimaxH3 的 H3 开关、prompt 预算与 comfy 前缀端点 / 预算，覆盖当前已安装 Plugin 的默认工作流之外的对应 字段；baseUrl
   * / promptAgentName 非 null 原样映射，null 且 H3 禁用时保留目标新默认（是禁用的新配置，不是历史绑定）。
   */
  private String comfyuiValues(PluginConfigSchema schema, boolean hasComfyBearer) {
    ObjectNode h3 = (ObjectNode) integrations.get("minimaxH3");
    boolean enabled = h3.get("enabled").booleanValue();
    if (enabled && !hasComfyBearer) {
      throw new IllegalArgumentException(COMFYUI_BEARER_REQUIRED);
    }
    ObjectNode values = (ObjectNode) readNode(schema.resolveValues("{}"));
    values.set("enabled", h3.get("enabled"));
    values.set("promptMaxWaitMillis", h3.get("promptMaxWaitMillis"));
    values.set("connectTimeoutMillis", h3.get("comfyConnectTimeoutMillis"));
    values.set("requestTimeoutMillis", h3.get("comfyRequestTimeoutMillis"));
    values.set("pollIntervalMillis", h3.get("comfyPollIntervalMillis"));
    values.set("maxWaitMillis", h3.get("comfyMaxWaitMillis"));
    if (h3.hasNonNull("promptAgentName")) {
      values.set("promptAgentName", h3.get("promptAgentName"));
    }
    if (h3.hasNonNull("comfyBaseUrl")) {
      values.set("baseUrl", h3.get("comfyBaseUrl"));
    }
    values.put("authMode", hasComfyBearer ? "BEARER" : "NONE");
    return schema.resolveValues(write(values));
  }

  /**
   * opencli：普通值从旧 openCliHub 逐键保留，并把旧 gptImage2 与 seedance 作为子对象；null baseUrl 与 null seedance
   * workspaceId 按目标可选字段语义省略，非 null 原样。enabled 只取旧 hub.enabled，绝不按 subfeature enabled 反推，也不复制秘密 或外部
   * instanceId。
   */
  private String openCliValues(PluginConfigSchema schema, boolean hasOpenCliApiKey) {
    ObjectNode hub = (ObjectNode) integrations.get("openCliHub");
    boolean enabled = hub.get("enabled").booleanValue();
    if (enabled && !hasOpenCliApiKey) {
      throw new IllegalArgumentException(OPENCLI_API_KEY_REQUIRED);
    }
    ObjectNode values = hub.deepCopy();
    if (!values.hasNonNull("baseUrl")) {
      values.remove("baseUrl");
    }
    values.set("gptImage2", integrations.get("gptImage2").deepCopy());
    ObjectNode seedance = ((ObjectNode) integrations.get("seedance")).deepCopy();
    if (!seedance.hasNonNull("workspaceId")) {
      seedance.remove("workspaceId");
    }
    values.set("seedance", seedance);
    return schema.resolveValues(write(values));
  }

  /** 去 integrations 的六节交给当前 codec 严格解码；任何失败收敛为固定消息，绝不附带原始 JSON 或 cause。 */
  private static SystemSettings decodeCurrentSections(ObjectNode root) {
    ObjectNode sixSections = root.deepCopy();
    sixSections.remove("integrations");
    try {
      return SETTINGS_CODEC.decode(write(sixSections));
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(SIX_SECTIONS_REJECTED);
    }
  }

  private static void requireComfyui(ObjectNode block) {
    requireFields(block, LEGACY_COMFYUI_FIELDS, Set.of("baseUrl"), COMFYUI_BLOCK);
    requireBoolean(block, "enabled", COMFYUI_BLOCK);
    requireTextOrNull(block, "baseUrl", COMFYUI_BLOCK);
    requireLong(block, "connectTimeoutMillis", COMFYUI_BLOCK);
    requireLong(block, "readTimeoutMillis", COMFYUI_BLOCK);
    requireLong(block, "websocketTimeoutMillis", COMFYUI_BLOCK);
    requireLong(block, "maxInputFileBytes", COMFYUI_BLOCK);
    // 无消费者的死块只接受旧 DEFAULT 退休，绝不当成 H3 endpoint / 预算 fallback。
    if (!isLegacyComfyuiDefault(block)) {
      throw new IllegalArgumentException(COMFYUI_BLOCK_NOT_DEFAULT);
    }
  }

  private static boolean isLegacyComfyuiDefault(ObjectNode block) {
    return !block.get("enabled").booleanValue()
        && !block.hasNonNull("baseUrl")
        && block.get("connectTimeoutMillis").longValue() == LEGACY_COMFYUI_CONNECT_TIMEOUT_MILLIS
        && block.get("readTimeoutMillis").longValue() == LEGACY_COMFYUI_READ_TIMEOUT_MILLIS
        && block.get("websocketTimeoutMillis").longValue()
            == LEGACY_COMFYUI_WEBSOCKET_TIMEOUT_MILLIS
        && block.get("maxInputFileBytes").longValue() == LEGACY_COMFYUI_MAX_INPUT_FILE_BYTES;
  }

  private static void requireOpenCliHub(ObjectNode block) {
    requireFields(block, LEGACY_OPENCLI_HUB_FIELDS, Set.of("baseUrl"), OPENCLI_HUB_BLOCK);
    requireBoolean(block, "enabled", OPENCLI_HUB_BLOCK);
    requireTextOrNull(block, "baseUrl", OPENCLI_HUB_BLOCK);
    requireLong(block, "connectTimeoutMillis", OPENCLI_HUB_BLOCK);
    requireLong(block, "requestTimeoutMillis", OPENCLI_HUB_BLOCK);
    requireLong(block, "longPollTimeoutMillis", OPENCLI_HUB_BLOCK);
    requireInt(block, "streamBufferBytes", OPENCLI_HUB_BLOCK);
    requireInt(block, "maxJsonResponseBytes", OPENCLI_HUB_BLOCK);
    requireInt(block, "maxErrorResponseBytes", OPENCLI_HUB_BLOCK);
    requireInt(block, "maxOutputChars", OPENCLI_HUB_BLOCK);
  }

  private static void requireSeedance(ObjectNode block) {
    requireFields(block, LEGACY_SEEDANCE_FIELDS, Set.of("workspaceId"), SEEDANCE_BLOCK);
    requireBoolean(block, "enabled", SEEDANCE_BLOCK);
    requireTextOrNull(block, "workspaceId", SEEDANCE_BLOCK);
    requireInt(block, "retry", SEEDANCE_BLOCK);
    requireLong(block, "hubExecutionTimeoutMillis", SEEDANCE_BLOCK);
    requireLong(block, "statusPollIntervalMillis", SEEDANCE_BLOCK);
    requireLong(block, "maxWaitMillis", SEEDANCE_BLOCK);
  }

  private static void requireGptImage2(ObjectNode block) {
    requireExactFields(block, LEGACY_GPT_IMAGE_2_FIELDS, GPT_IMAGE_2_BLOCK);
    requireBoolean(block, "paidEnabled", GPT_IMAGE_2_BLOCK);
    requireInt(block, "askTimeoutSeconds", GPT_IMAGE_2_BLOCK);
    requireLong(block, "hubExecutionTimeoutMillis", GPT_IMAGE_2_BLOCK);
    requireLong(block, "maxWaitMillis", GPT_IMAGE_2_BLOCK);
  }

  private static void requireMiniMaxH3(ObjectNode block) {
    requireFields(
        block,
        LEGACY_MINIMAX_H3_FIELDS,
        Set.of("promptAgentName", "comfyBaseUrl"),
        MINIMAX_H3_BLOCK);
    boolean enabled = requireBoolean(block, "enabled", MINIMAX_H3_BLOCK);
    String promptAgentName = requireTextOrNull(block, "promptAgentName", MINIMAX_H3_BLOCK);
    requireLong(block, "promptMaxWaitMillis", MINIMAX_H3_BLOCK);
    String comfyBaseUrl = requireTextOrNull(block, "comfyBaseUrl", MINIMAX_H3_BLOCK);
    requireLong(block, "comfyConnectTimeoutMillis", MINIMAX_H3_BLOCK);
    requireLong(block, "comfyRequestTimeoutMillis", MINIMAX_H3_BLOCK);
    requireLong(block, "comfyPollIntervalMillis", MINIMAX_H3_BLOCK);
    requireLong(block, "comfyMaxWaitMillis", MINIMAX_H3_BLOCK);
    if (enabled && (promptAgentName == null || comfyBaseUrl == null)) {
      throw new IllegalArgumentException(MINIMAX_H3_BLOCK);
    }
  }

  private static ObjectNode parseObject(String json) {
    JsonNode root = parseStrict(json);
    if (!(root instanceof ObjectNode object)) {
      throw new IllegalArgumentException(SECTIONS);
    }
    return object;
  }

  private static JsonNode parseStrict(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException(MALFORMED);
    }
    try {
      JsonNode root = MAPPER.readTree(json);
      // Jackson 3 空白输入返回 MissingNode；严格重复键 / 尾随 token / 畸形结构在 readTree 即失败。
      if (root == null || root.isMissingNode()) {
        throw new IllegalArgumentException(MALFORMED);
      }
      return root;
    } catch (JacksonException error) {
      throw new IllegalArgumentException(MALFORMED);
    }
  }

  private static ObjectNode requireBlock(ObjectNode parent, String field, String message) {
    JsonNode node = parent.get(field);
    if (!(node instanceof ObjectNode object)) {
      throw new IllegalArgumentException(message);
    }
    return object;
  }

  private static void requireExactFields(ObjectNode node, Set<String> fields, String message) {
    requireFields(node, fields, Set.of(), message);
  }

  private static void requireFields(
      ObjectNode node, Set<String> fields, Set<String> nullable, String message) {
    Set<String> names = new LinkedHashSet<>();
    node.propertyNames().forEach(names::add);
    if (!fields.containsAll(names)
        || fields.stream().anyMatch(field -> !nullable.contains(field) && !names.contains(field))) {
      throw new IllegalArgumentException(message);
    }
  }

  private static boolean requireBoolean(ObjectNode node, String field, String message) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException(message);
    }
    return value.booleanValue();
  }

  private static String requireTextOrNull(ObjectNode node, String field, String message) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new IllegalArgumentException(message);
    }
    return value.stringValue();
  }

  private static long requireLong(ObjectNode node, String field, String message) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(message);
    }
    return value.longValue();
  }

  private static int requireInt(ObjectNode node, String field, String message) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(message);
    }
    return value.intValue();
  }

  private static JsonNode readNode(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot read generated legacy system settings JSON");
    }
  }

  private static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot write legacy system settings JSON");
    }
  }
}

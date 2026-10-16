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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一次性离线升级工具专用的旧七节 system settings 到当前六节聚合与 Plugin 普通值的纯转换。
 *
 * <p>两阶段、无宿主副作用：
 *
 * <ul>
 *   <li>构造：严格解析并校验一次旧 {@code config} JSON（顶层恰为 tool / aiRuntime / environment / network /
 *       integrations / storageMedia / advanced 七节；{@code integrations} 恰为 comfyui / openCliHub /
 *       seedance / gptImage2 / minimaxH3 五块，各块按旧 Codec 的精确字段集与 boolean / string-or-null / integral
 *       类型校验）。去掉 {@code integrations} 后的六节只交给当前 {@link SystemSettingsCodec} 解码校验，本类不复制它的模型、codec
 *       或缺省逻辑。
 *   <li>{@link #pluginValues(StudioPluginRegistry, boolean, boolean)}：用调用方以当前六节 settings 现场打开的真实
 *       {@link StudioPluginRegistry} 作为唯一安装 authority，把旧 integrations 的普通值投影为每个实际已安装 Plugin 的配置
 *       JSON。
 * </ul>
 *
 * <p>边界与事实约束：
 *
 * <ul>
 *   <li>旧 {@code integrations.comfyui} 块在当前运行时没有任何消费者：只有字段与值全部等于旧 {@code Comfyui.DEFAULT}
 *       才可安全退休；任何非默认值都明确拒绝，要求操作人另行保留 / 审查后恢复，绝不把它当作 H3 endpoint 或预算 fallback。
 *   <li>H3 端点与预算只来自旧 {@code integrations.minimaxH3}，并覆盖当前已安装 comfyui Plugin 自己声明的默认工作流；新增 workflow
 *       只来自当前真实已装 Plugin 默认，不补历史 binding。
 *   <li>普通值只按目标 PluginSchema 归一化校验；完整业务区间 / URL / 工作流校验仍由随后现有的 {@code
 *       OfflinePluginCatalog.prepareInitial} 真正 configure 完成，本类不重复各 Plugin 私有 Settings 解析，也不做任何
 *       clamp / strip / fallback。
 *   <li>本类不读取密钥、不编造旧侧不存在的秘密、不推断历史 identity；对外失败只携带固定阶段 / 字段消息，绝不泄漏原始 JSON、值或 Throwable cause。
 * </ul>
 *
 * <p>严格拒绝重复字段与尾随 token；自由 JSON number 按精确十进制保留。
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

  /** 去掉 integrations 后交给当前 codec 的六节名，顺序即当前聚合声明顺序。 */
  private static final List<String> CURRENT_SECTIONS =
      List.of("tool", "aiRuntime", "environment", "network", "storageMedia", "advanced");

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
    ObjectNode h3 = requireBlock(integrations, "minimaxH3", MINIMAX_H3_BLOCK);
    boolean enabled = requireBoolean(h3, "enabled", MINIMAX_H3_BLOCK);
    String promptAgentName = requireTextOrNull(h3, "promptAgentName", MINIMAX_H3_BLOCK);
    String comfyBaseUrl = requireTextOrNull(h3, "comfyBaseUrl", MINIMAX_H3_BLOCK);
    if (enabled && !hasComfyBearer) {
      throw new IllegalArgumentException(COMFYUI_BEARER_REQUIRED);
    }
    ObjectNode values = (ObjectNode) readNode(schema.resolveValues("{}"));
    values.put("enabled", enabled);
    values.put("promptMaxWaitMillis", requireLong(h3, "promptMaxWaitMillis", MINIMAX_H3_BLOCK));
    values.put(
        "connectTimeoutMillis", requireLong(h3, "comfyConnectTimeoutMillis", MINIMAX_H3_BLOCK));
    values.put(
        "requestTimeoutMillis", requireLong(h3, "comfyRequestTimeoutMillis", MINIMAX_H3_BLOCK));
    values.put("pollIntervalMillis", requireLong(h3, "comfyPollIntervalMillis", MINIMAX_H3_BLOCK));
    values.put("maxWaitMillis", requireLong(h3, "comfyMaxWaitMillis", MINIMAX_H3_BLOCK));
    if (promptAgentName != null) {
      values.put("promptAgentName", promptAgentName);
    }
    if (comfyBaseUrl != null) {
      values.put("baseUrl", comfyBaseUrl);
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
    ObjectNode hub = requireBlock(integrations, "openCliHub", OPENCLI_HUB_BLOCK);
    ObjectNode seedance = requireBlock(integrations, "seedance", SEEDANCE_BLOCK);
    ObjectNode gptImage2 = requireBlock(integrations, "gptImage2", GPT_IMAGE_2_BLOCK);
    boolean enabled = requireBoolean(hub, "enabled", OPENCLI_HUB_BLOCK);
    if (enabled && !hasOpenCliApiKey) {
      throw new IllegalArgumentException(OPENCLI_API_KEY_REQUIRED);
    }
    ObjectNode values = MAPPER.createObjectNode();
    values.put("enabled", enabled);
    values.put("connectTimeoutMillis", requireLong(hub, "connectTimeoutMillis", OPENCLI_HUB_BLOCK));
    values.put("requestTimeoutMillis", requireLong(hub, "requestTimeoutMillis", OPENCLI_HUB_BLOCK));
    values.put(
        "longPollTimeoutMillis", requireLong(hub, "longPollTimeoutMillis", OPENCLI_HUB_BLOCK));
    values.put("streamBufferBytes", requireInt(hub, "streamBufferBytes", OPENCLI_HUB_BLOCK));
    values.put("maxJsonResponseBytes", requireInt(hub, "maxJsonResponseBytes", OPENCLI_HUB_BLOCK));
    values.put(
        "maxErrorResponseBytes", requireInt(hub, "maxErrorResponseBytes", OPENCLI_HUB_BLOCK));
    values.put("maxOutputChars", requireInt(hub, "maxOutputChars", OPENCLI_HUB_BLOCK));
    String baseUrl = requireTextOrNull(hub, "baseUrl", OPENCLI_HUB_BLOCK);
    if (baseUrl != null) {
      values.put("baseUrl", baseUrl);
    }

    ObjectNode gptValues = values.putObject("gptImage2");
    gptValues.put("paidEnabled", requireBoolean(gptImage2, "paidEnabled", GPT_IMAGE_2_BLOCK));
    gptValues.put(
        "askTimeoutSeconds", requireInt(gptImage2, "askTimeoutSeconds", GPT_IMAGE_2_BLOCK));
    gptValues.put(
        "hubExecutionTimeoutMillis",
        requireLong(gptImage2, "hubExecutionTimeoutMillis", GPT_IMAGE_2_BLOCK));
    gptValues.put("maxWaitMillis", requireLong(gptImage2, "maxWaitMillis", GPT_IMAGE_2_BLOCK));

    ObjectNode seedanceValues = values.putObject("seedance");
    seedanceValues.put("enabled", requireBoolean(seedance, "enabled", SEEDANCE_BLOCK));
    seedanceValues.put("retry", requireInt(seedance, "retry", SEEDANCE_BLOCK));
    seedanceValues.put(
        "hubExecutionTimeoutMillis",
        requireLong(seedance, "hubExecutionTimeoutMillis", SEEDANCE_BLOCK));
    seedanceValues.put(
        "statusPollIntervalMillis",
        requireLong(seedance, "statusPollIntervalMillis", SEEDANCE_BLOCK));
    seedanceValues.put("maxWaitMillis", requireLong(seedance, "maxWaitMillis", SEEDANCE_BLOCK));
    String workspaceId = requireTextOrNull(seedance, "workspaceId", SEEDANCE_BLOCK);
    if (workspaceId != null) {
      seedanceValues.put("workspaceId", workspaceId);
    }
    return schema.resolveValues(write(values));
  }

  /** 去 integrations 的六节交给当前 codec 严格解码；任何失败收敛为固定消息，绝不附带原始 JSON 或 cause。 */
  private static SystemSettings decodeCurrentSections(ObjectNode root) {
    ObjectNode sixSections = MAPPER.createObjectNode();
    for (String section : CURRENT_SECTIONS) {
      sixSections.set(section, root.get(section));
    }
    try {
      return SETTINGS_CODEC.decode(write(sixSections));
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(SIX_SECTIONS_REJECTED);
    }
  }

  private static void requireComfyui(ObjectNode block) {
    requireExactFields(block, LEGACY_COMFYUI_FIELDS, COMFYUI_BLOCK);
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
    return !requireBoolean(block, "enabled", COMFYUI_BLOCK)
        && requireTextOrNull(block, "baseUrl", COMFYUI_BLOCK) == null
        && requireLong(block, "connectTimeoutMillis", COMFYUI_BLOCK)
            == LEGACY_COMFYUI_CONNECT_TIMEOUT_MILLIS
        && requireLong(block, "readTimeoutMillis", COMFYUI_BLOCK)
            == LEGACY_COMFYUI_READ_TIMEOUT_MILLIS
        && requireLong(block, "websocketTimeoutMillis", COMFYUI_BLOCK)
            == LEGACY_COMFYUI_WEBSOCKET_TIMEOUT_MILLIS
        && requireLong(block, "maxInputFileBytes", COMFYUI_BLOCK)
            == LEGACY_COMFYUI_MAX_INPUT_FILE_BYTES;
  }

  private static void requireOpenCliHub(ObjectNode block) {
    requireExactFields(block, LEGACY_OPENCLI_HUB_FIELDS, OPENCLI_HUB_BLOCK);
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
    requireExactFields(block, LEGACY_SEEDANCE_FIELDS, SEEDANCE_BLOCK);
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
    requireExactFields(block, LEGACY_MINIMAX_H3_FIELDS, MINIMAX_H3_BLOCK);
    requireBoolean(block, "enabled", MINIMAX_H3_BLOCK);
    requireTextOrNull(block, "promptAgentName", MINIMAX_H3_BLOCK);
    requireLong(block, "promptMaxWaitMillis", MINIMAX_H3_BLOCK);
    requireTextOrNull(block, "comfyBaseUrl", MINIMAX_H3_BLOCK);
    requireLong(block, "comfyConnectTimeoutMillis", MINIMAX_H3_BLOCK);
    requireLong(block, "comfyRequestTimeoutMillis", MINIMAX_H3_BLOCK);
    requireLong(block, "comfyPollIntervalMillis", MINIMAX_H3_BLOCK);
    requireLong(block, "comfyMaxWaitMillis", MINIMAX_H3_BLOCK);
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
    Set<String> names = new LinkedHashSet<>();
    node.propertyNames().forEach(names::add);
    if (!names.equals(fields)) {
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
    if (value == null) {
      throw new IllegalArgumentException(message);
    }
    if (value.isNull()) {
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

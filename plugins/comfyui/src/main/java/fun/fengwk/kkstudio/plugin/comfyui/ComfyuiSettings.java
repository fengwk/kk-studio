package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;

import java.util.List;
import java.util.Objects;

/**
 * ComfyUI 插件私有配置值对象：连接与认证、Prompt 预算与冻结工作流集合。
 *
 * <p>默认值唯一来源是 {@link ComfyuiPlugin#CONFIGURATION_SCHEMA}；本模型只做业务校验并产出不可变快照，不做 HTTP、Prompt 或生成，
 * 也不读取任何秘密。连接与超时预算沿用平台既有有界安全值，不放大也不臆造更宽的时间/大小。
 */
record ComfyuiSettings(
    boolean enabled,
    String baseUrl,
    AuthMode authMode,
    long connectTimeoutMillis,
    long requestTimeoutMillis,
    long pollIntervalMillis,
    long maxWaitMillis,
    String promptAgentName,
    long promptMaxWaitMillis,
    List<ComfyuiWorkflow> workflows) {

  /** 出网认证模式：{@code NONE} 显式不发 Bearer，{@code BEARER} 每次请求独立解析 scoped 凭据。 */
  enum AuthMode {
    NONE,
    BEARER
  }

  private static final int MAX_BASE_URL_CHARS = 2048;
  private static final int MAX_AGENT_NAME_CHARS = 64;

  ComfyuiSettings {
    baseUrl = requireBoundedText(baseUrl, MAX_BASE_URL_CHARS, "baseUrl");
    authMode = Objects.requireNonNull(authMode, "authMode");
    requireBounded(connectTimeoutMillis, 60_000L, "connectTimeoutMillis");
    requireBounded(requestTimeoutMillis, 5L * 60 * 1000, "requestTimeoutMillis");
    requireBounded(pollIntervalMillis, 60L * 1000, "pollIntervalMillis");
    requireBounded(maxWaitMillis, 2L * 60 * 60 * 1000, "maxWaitMillis");
    requireBounded(promptMaxWaitMillis, 30L * 60 * 1000, "promptMaxWaitMillis");
    if (pollIntervalMillis >= maxWaitMillis) {
      throw new IllegalArgumentException("pollIntervalMillis must be less than maxWaitMillis");
    }
    promptAgentName = requireBoundedText(promptAgentName, MAX_AGENT_NAME_CHARS, "promptAgentName");
    workflows = List.copyOf(Objects.requireNonNull(workflows, "workflows"));
  }

  static ComfyuiSettings parse(String valuesJson) {
    JsonNode root = JsonValues.readTree(valuesJson);
    if (!(root instanceof ObjectNode node)) {
      throw new IllegalArgumentException("ComfyUI plugin configuration must be a JSON object");
    }
    return new ComfyuiSettings(
        bool(node, "enabled"),
        text(node, "baseUrl"),
        authMode(text(node, "authMode")),
        integer(node, "connectTimeoutMillis"),
        integer(node, "requestTimeoutMillis"),
        integer(node, "pollIntervalMillis"),
        integer(node, "maxWaitMillis"),
        text(node, "promptAgentName"),
        integer(node, "promptMaxWaitMillis"),
        ComfyuiWorkflowParser.parse(required(node, "workflows")));
  }

  private static AuthMode authMode(String value) {
    try {
      return AuthMode.valueOf(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("authMode must be NONE or BEARER");
    }
  }

  private static JsonNode required(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static boolean bool(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(field + " must be a boolean");
    }
    return value.booleanValue();
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static long integer(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    return value.longValue();
  }

  private static void requireBounded(long value, long max, String field) {
    if (value < 1L || value > max) {
      throw new IllegalArgumentException(field + " must be between 1 and " + max);
    }
  }

  private static String requireBoundedText(String value, int maxChars, String field) {
    if (value == null || value.isBlank() || !value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must be non-blank unpadded text");
    }
    if (value.length() > maxChars) {
      throw new IllegalArgumentException(field + " must not exceed " + maxChars + " characters");
    }
    return value;
  }
}

package fun.fengwk.kkstudio.harness.daemon.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 严格 Local MCP 参数解析器：
 *
 * <ul>
 *   <li>开启重复键检测，拒绝重复字段；
 *   <li>严格拒绝未知字段与畸形请求结构；
 *   <li>顶层配置形状固定为 type=local、environmentId、command、cwd 四个必填字段，env/enabled/timeoutMillis 可选并带默认值；
 *   <li>{@code serverId} 与 {@code environmentId} 必须是 canonical 小写带连字符 UUID；{@code configVersion} 与
 *       {@code timeoutMillis} 必须是可无损转 {@code long} 的整数；
 *   <li>拒绝 remote、相对 cwd、空 command；
 *   <li>对 command、cwd、env 中的字符串执行整值 ${VAR} 引用解析（从环境提供方读取），缺失时报错，不执行复合表达式；
 *   <li>错误文本与异常绝不回显 command、cwd、env 或 payload 字段值。
 * </ul>
 */
public final class DaemonLocalMcpParser {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Pattern WHOLE_VAR_PATTERN =
      Pattern.compile("^\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}$");

  /** {@code enabled} 缺省值：未显式关闭时 MCP server 视为可用。 */
  static final boolean DEFAULT_ENABLED = true;

  /** {@code timeoutMillis} 缺省值：60 秒。 */
  static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;

  private static final Set<String> DISCOVER_REQUIRED_KEYS =
      Set.of("serverId", "configVersion", "config");
  private static final Set<String> CALL_REQUIRED_KEYS =
      Set.of("serverId", "configVersion", "config", "toolName", "arguments");

  /** config 允许出现的全部字段。 */
  private static final Set<String> CONFIG_ALLOWED_KEYS =
      Set.of("type", "environmentId", "command", "cwd", "env", "enabled", "timeoutMillis");

  /** config 必须出现的字段：其余字段有默认值，缺失不是错误。 */
  private static final Set<String> CONFIG_REQUIRED_KEYS =
      Set.of("type", "environmentId", "command", "cwd");

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private final Function<String, String> envProvider;
  private final Supplier<String> boundEnvironmentId;

  public DaemonLocalMcpParser() {
    this(System::getenv, () -> null);
  }

  public DaemonLocalMcpParser(Function<String, String> envProvider) {
    this(envProvider, () -> null);
  }

  /**
   * @param envProvider 解析整值 {@code ${VAR}} 引用的环境变量来源
   * @param boundEnvironmentId 本 Daemon 当前绑定的 Environment ID 供应器；返回 {@code null} 表示绑定未知（WELCOME 之前），
   *     此时只校验 canonical UUID 形状，一旦绑定可用就必须严格匹配，避免把请求交给错误的环境身份
   */
  public DaemonLocalMcpParser(
      Function<String, String> envProvider, Supplier<String> boundEnvironmentId) {
    this.envProvider = Objects.requireNonNull(envProvider, "envProvider");
    this.boundEnvironmentId = Objects.requireNonNull(boundEnvironmentId, "boundEnvironmentId");
  }

  /** 解析 mcp.local.discover 顶层参数。 */
  public DaemonLocalMcpConfig parseDiscover(String argumentsJson) {
    JsonNode root = parseJson(argumentsJson);
    validateExactFields(root, DISCOVER_REQUIRED_KEYS, "discover request");
    return parseConfig(root);
  }

  /** 解析 mcp.local.call 顶层参数。 */
  public DaemonLocalMcpCallRequest parseCall(String argumentsJson) {
    JsonNode root = parseJson(argumentsJson);
    validateExactFields(root, CALL_REQUIRED_KEYS, "call request");

    DaemonLocalMcpConfig config = parseConfig(root);
    JsonNode toolNameNode = root.get("toolName");
    if (toolNameNode == null || !toolNameNode.isTextual() || toolNameNode.asText().isBlank()) {
      throw new DaemonMcpValidationException("toolName must be non-blank string");
    }
    String toolName = toolNameNode.asText();

    JsonNode argumentsNode = root.get("arguments");
    if (argumentsNode == null || !argumentsNode.isObject()) {
      throw new DaemonMcpValidationException("arguments must be a JSON object");
    }
    String toolArgumentsJson = argumentsNode.toString();

    return new DaemonLocalMcpCallRequest(config, toolName, toolArgumentsJson);
  }

  private DaemonLocalMcpConfig parseConfig(JsonNode root) {
    String serverId = canonicalUuid(root.get("serverId"), "serverId");
    long configVersion = requiredNonNegativeLong(root.get("configVersion"), "configVersion");

    JsonNode configNode = root.get("config");
    if (configNode == null || !configNode.isObject()) {
      throw new DaemonMcpValidationException("config must be a JSON object");
    }
    validateAllowedFields(configNode, "config");

    JsonNode typeNode = configNode.get("type");
    if (typeNode == null || !typeNode.isTextual() || !"local".equals(typeNode.asText())) {
      throw new DaemonMcpValidationException("config type must be 'local'");
    }

    String environmentId = canonicalUuid(configNode.get("environmentId"), "environmentId");
    String bound = boundEnvironmentId.get();
    if (bound != null && !bound.equals(environmentId)) {
      // 绑定已知时请求必须指向本 Daemon 所属 Environment：错误目标意味着会把调用路由到别的环境身份。
      throw new DaemonMcpValidationException("environmentId does not match this daemon binding");
    }

    boolean enabled = DEFAULT_ENABLED;
    JsonNode enabledNode = configNode.get("enabled");
    if (enabledNode != null) {
      if (!enabledNode.isBoolean()) {
        throw new DaemonMcpValidationException("enabled must be a boolean");
      }
      enabled = enabledNode.asBoolean();
    }
    if (!enabled) {
      // 显式关闭的 server 不接受任何调用；默认值只为省略字段的配置提供可用性。
      throw new DaemonMcpValidationException("mcp server is disabled");
    }

    long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    JsonNode timeoutNode = configNode.get("timeoutMillis");
    if (timeoutNode != null) {
      timeoutMillis = requiredPositiveLong(timeoutNode, "timeoutMillis");
    }

    JsonNode commandNode = configNode.get("command");
    if (commandNode == null || !commandNode.isArray() || commandNode.isEmpty()) {
      throw new DaemonMcpValidationException("command must be a non-empty array");
    }
    List<String> command = new ArrayList<>(commandNode.size());
    for (JsonNode argNode : commandNode) {
      if (!argNode.isTextual() || argNode.asText().isBlank()) {
        throw new DaemonMcpValidationException("command argument must be non-blank string");
      }
      String resolvedArg = resolveWholeVar(argNode.asText());
      if (resolvedArg.isBlank()) {
        throw new DaemonMcpValidationException(
            "resolved command argument must be non-blank string");
      }
      command.add(resolvedArg);
    }

    JsonNode cwdNode = configNode.get("cwd");
    if (cwdNode == null || !cwdNode.isTextual() || cwdNode.asText().isBlank()) {
      throw new DaemonMcpValidationException("cwd must be non-blank string");
    }
    String resolvedCwd = resolveWholeVar(cwdNode.asText());
    requireAbsolutePath(resolvedCwd);

    Map<String, String> env = Map.of();
    JsonNode envNode = configNode.get("env");
    if (envNode != null) {
      if (!envNode.isObject()) {
        throw new DaemonMcpValidationException("env must be a JSON object");
      }
      Map<String, String> parsedEnv = new LinkedHashMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = envNode.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        if (!entry.getValue().isTextual()) {
          throw new DaemonMcpValidationException("env value must be string");
        }
        parsedEnv.put(entry.getKey(), resolveWholeVar(entry.getValue().asText()));
      }
      env = parsedEnv;
    }

    return new DaemonLocalMcpConfig(
        serverId, configVersion, environmentId, command, resolvedCwd, env, enabled, timeoutMillis);
  }

  /**
   * 解析 canonical 小写带连字符 UUID 文本。
   *
   * <p>大小写变体、无连字符或非法形状都必须失败：identity 只接受唯一的 canonical 表示，否则同一个 server 可能被写成多种字符串而绕过共享与 fencing。
   */
  private static String canonicalUuid(JsonNode node, String field) {
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      throw new DaemonMcpValidationException(field + " must be non-blank string");
    }
    String raw = node.asText();
    UUID parsed;
    try {
      parsed = UUID.fromString(raw);
    } catch (IllegalArgumentException error) {
      throw new DaemonMcpValidationException(field + " must be a canonical UUID string");
    }
    if (!parsed.toString().equals(raw)) {
      throw new DaemonMcpValidationException(field + " must be a canonical dashed lowercase UUID");
    }
    return raw;
  }

  /** 解析必须可无损转换的整数；浮点、字符串、超出 long 范围的超大整数都必须失败。 */
  private static long requiredNonNegativeLong(JsonNode node, String field) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new DaemonMcpValidationException(field + " must be an integer within long range");
    }
    long value = node.asLong();
    if (value < 0) {
      throw new DaemonMcpValidationException(field + " must not be negative");
    }
    return value;
  }

  /** 解析必须为正的可无损转换整数。 */
  private static long requiredPositiveLong(JsonNode node, String field) {
    long value = requiredNonNegativeLong(node, field);
    if (value == 0) {
      throw new DaemonMcpValidationException(field + " must be positive integer");
    }
    return value;
  }

  private static void requireAbsolutePath(String cwd) {
    try {
      if (!Path.of(cwd).isAbsolute()) {
        throw new DaemonMcpValidationException("cwd must be an absolute path");
      }
    } catch (DaemonMcpValidationException error) {
      throw error;
    } catch (RuntimeException error) {
      throw new DaemonMcpValidationException("cwd is not a valid absolute path");
    }
  }

  private String resolveWholeVar(String value) {
    if (value == null) {
      return null;
    }
    Matcher matcher = WHOLE_VAR_PATTERN.matcher(value);
    if (matcher.matches()) {
      String varName = matcher.group(1);
      String resolved = envProvider.apply(varName);
      if (resolved == null) {
        throw new DaemonMcpValidationException("missing environment variable");
      }
      return resolved;
    }
    return value;
  }

  private static JsonNode parseJson(String json) {
    if (json == null || json.isBlank()) {
      throw new DaemonMcpValidationException("arguments must not be blank");
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(json);
      if (node == null || !node.isObject()) {
        throw new DaemonMcpValidationException("arguments must be a JSON object");
      }
      return node;
    } catch (JsonProcessingException error) {
      // 不保留 Jackson cause：其消息会回显原始文本片段，可能夹带 payload 中的敏感字段值。
      throw new DaemonMcpValidationException("malformed json arguments");
    }
  }

  private static void validateExactFields(JsonNode node, Set<String> expectedKeys, String context) {
    Set<String> actualKeys = fieldNames(node);
    if (!actualKeys.equals(expectedKeys)) {
      throw new DaemonMcpValidationException("invalid fields in " + context);
    }
  }

  /** config 只校验「无未知字段 + 必需字段存在」：可选字段缺失走默认值，不构成错误。 */
  private static void validateAllowedFields(JsonNode node, String context) {
    Set<String> actualKeys = fieldNames(node);
    if (!CONFIG_ALLOWED_KEYS.containsAll(actualKeys)) {
      throw new DaemonMcpValidationException("unknown fields in " + context);
    }
    if (!actualKeys.containsAll(CONFIG_REQUIRED_KEYS)) {
      throw new DaemonMcpValidationException("missing required fields in " + context);
    }
  }

  private static Set<String> fieldNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    Iterator<String> fieldNames = node.fieldNames();
    while (fieldNames.hasNext()) {
      names.add(fieldNames.next());
    }
    return names;
  }
}

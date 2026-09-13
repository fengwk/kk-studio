package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.platform.catalog.mcp.McpStableIds;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.LocalConnectionConfig;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionConfig;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.RemoteConnectionConfig;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 严格单份 MCP Server 配置 JSON 解析器与规范化序列化器。
 *
 * <p>安全边界：错误信息中绝不泄露 URL、环境变量值、Bearer token 或敏感凭据。
 */
public final class McpConfigParser {

  private static final String RESOURCE = "mcp_server";
  public static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;
  public static final int URL_MAX_LENGTH = 2048;
  private static final int CWD_MAX_LENGTH = 2048;

  private static final Pattern WHOLE_VAR_PATTERN =
      Pattern.compile("^\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}$");
  private static final Pattern EMBEDDED_VAR_PATTERN =
      Pattern.compile(
          ".*(?:\\$\\{[a-zA-Z_][a-zA-Z0-9_]*}|\\$[a-zA-Z_][a-zA-Z0-9_]*|%[a-zA-Z_][a-zA-Z0-9_]*%).*");

  private static final Set<String> REMOTE_ALLOWED_KEYS =
      Set.of("type", "enabled", "timeoutMillis", "url", "headers");
  private static final Set<String> LOCAL_ALLOWED_KEYS =
      Set.of("type", "environmentId", "enabled", "timeoutMillis", "command", "cwd", "env");

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private McpConfigParser() {}

  /** 解析外部严格 JSON 配置文本。 */
  public static ParsedMcpConfig parse(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      throw new AiValidationException(RESOURCE, "configJson must not be blank");
    }
    JsonNode root = parseJsonTree(configJson);
    if (!root.isObject()) {
      throw new AiValidationException(RESOURCE, "configJson must be a JSON object");
    }

    JsonNode typeNode = root.get("type");
    if (typeNode == null || !typeNode.isTextual() || typeNode.asText().isBlank()) {
      throw new AiValidationException(RESOURCE, "type is required and must be 'remote' or 'local'");
    }
    McpConnectionType connectionType = McpConnectionType.fromExternal(typeNode.asText());

    boolean enabled = true;
    if (root.has("enabled")) {
      JsonNode enabledNode = root.get("enabled");
      if (!enabledNode.isBoolean()) {
        throw new AiValidationException(RESOURCE, "enabled must be a boolean");
      }
      enabled = enabledNode.asBoolean();
    }

    long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    if (root.has("timeoutMillis")) {
      JsonNode timeoutNode = root.get("timeoutMillis");
      if (!timeoutNode.isIntegralNumber() || !timeoutNode.canConvertToLong()) {
        throw new AiValidationException(
            RESOURCE, "timeoutMillis must be an integer within long range");
      }
      timeoutMillis = timeoutNode.asLong();
      if (timeoutMillis <= 0) {
        throw new AiValidationException(
            RESOURCE, "timeoutMillis must be positive: " + timeoutMillis);
      }
    }

    if (connectionType == McpConnectionType.REMOTE) {
      validateAllowedKeys(root, REMOTE_ALLOWED_KEYS, "remote");
      String url = validateRemoteUrl(root.get("url"));
      Map<String, String> headers = parseStringMap(root.get("headers"), "headers");
      RemoteConnectionConfig connectionConfig = new RemoteConnectionConfig(url, headers);
      String connectionConfigJson = serializeConnectionConfig(connectionConfig);
      return new ParsedMcpConfig(
          connectionType, null, connectionConfigJson, enabled, timeoutMillis, connectionConfig);
    } else {
      validateAllowedKeys(root, LOCAL_ALLOWED_KEYS, "local");
      JsonNode envNode = root.get("environmentId");
      if (envNode == null || !envNode.isTextual() || envNode.asText().isBlank()) {
        throw new AiValidationException(
            RESOURCE, "environmentId is required for local connection type");
      }
      UUID environmentId =
          McpStableIds.requireCanonicalDashedUuid(envNode.asText(), "environmentId");

      List<String> command = parseCommand(root.get("command"));
      String cwd = validateCwd(root.get("cwd"));
      Map<String, String> env = parseStringMap(root.get("env"), "env");
      LocalConnectionConfig connectionConfig = new LocalConnectionConfig(command, cwd, env);
      String connectionConfigJson = serializeConnectionConfig(connectionConfig);
      return new ParsedMcpConfig(
          connectionType,
          environmentId,
          connectionConfigJson,
          enabled,
          timeoutMillis,
          connectionConfig);
    }
  }

  /** 从 DB 行恢复领域连接配置。 */
  public static McpConnectionConfig parseConnectionConfig(
      McpConnectionType type, String connectionConfigJson) {
    Objects.requireNonNull(type, "type");
    if (connectionConfigJson == null || connectionConfigJson.isBlank()) {
      throw new IllegalStateException("connectionConfigJson in database must not be blank");
    }
    JsonNode root = parseJsonTree(connectionConfigJson);
    if (type == McpConnectionType.REMOTE) {
      String url = root.path("url").asText("");
      Map<String, String> headers = parseStringMap(root.get("headers"), "headers");
      return new RemoteConnectionConfig(url, headers);
    } else {
      List<String> command = new ArrayList<>();
      if (root.has("command") && root.get("command").isArray()) {
        for (JsonNode item : root.get("command")) {
          command.add(item.asText(""));
        }
      }
      String cwd = root.path("cwd").asText("");
      Map<String, String> env = parseStringMap(root.get("env"), "env");
      return new LocalConnectionConfig(command, cwd, env);
    }
  }

  /** 将完整 Server 模型生成对外回显的标准规范化完整 configJson（带默认值，不解析 ${VAR}）。 */
  public static String toFullConfigJson(McpServer server) {
    Objects.requireNonNull(server, "server");
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("type", server.getConnectionType().toExternal());
    if (server.getConnectionType() == McpConnectionType.LOCAL) {
      root.put(
          "environmentId",
          server.getEnvironmentId() == null ? "" : server.getEnvironmentId().toString());
    }
    root.put("enabled", server.isEnabled());
    root.put("timeoutMillis", server.getTimeoutMillis());

    McpConnectionConfig config =
        parseConnectionConfig(server.getConnectionType(), server.getConnectionConfig());
    if (config instanceof RemoteConnectionConfig remote) {
      root.put("url", remote.url());
      ObjectNode headersNode = root.putObject("headers");
      remote.headers().forEach(headersNode::put);
    } else if (config instanceof LocalConnectionConfig local) {
      ArrayNode cmdNode = root.putArray("command");
      local.command().forEach(cmdNode::add);
      root.put("cwd", local.cwd());
      ObjectNode envNode = root.putObject("env");
      local.env().forEach(envNode::put);
    }
    return root.toString();
  }

  /** 解析并替换 Remote Header 中的整值 ${VAR} 占位符；Local 占位符由 Daemon 处理。 */
  public static Map<String, String> resolveRemoteHeaders(
      Map<String, String> headers, Function<String, String> envProvider) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    Objects.requireNonNull(envProvider, "envProvider");
    Map<String, String> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (value != null) {
        Matcher matcher = WHOLE_VAR_PATTERN.matcher(value.trim());
        if (matcher.matches()) {
          String varName = matcher.group(1);
          String resolvedVal = envProvider.apply(varName);
          if (resolvedVal == null || resolvedVal.isBlank()) {
            throw new AiValidationException(
                RESOURCE, "required environment variable not found: " + varName);
          }
          resolved.put(key, resolvedVal);
          continue;
        }
      }
      resolved.put(key, value);
    }
    return Collections.unmodifiableMap(resolved);
  }

  private static String serializeConnectionConfig(McpConnectionConfig config) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    if (config instanceof RemoteConnectionConfig remote) {
      node.put("url", remote.url());
      ObjectNode headers = node.putObject("headers");
      remote.headers().forEach(headers::put);
    } else if (config instanceof LocalConnectionConfig local) {
      ArrayNode cmd = node.putArray("command");
      local.command().forEach(cmd::add);
      node.put("cwd", local.cwd());
      ObjectNode env = node.putObject("env");
      local.env().forEach(env::put);
    }
    return node.toString();
  }

  private static void validateAllowedKeys(
      JsonNode root, Set<String> allowedKeys, String connectionType) {
    Set<String> extraKeys = new HashSet<>();
    Iterator<String> it = root.fieldNames();
    while (it.hasNext()) {
      String field = it.next();
      if (!allowedKeys.contains(field)) {
        extraKeys.add(field);
      }
    }
    if (!extraKeys.isEmpty()) {
      throw new AiValidationException(
          RESOURCE, "unexpected fields for " + connectionType + " mcp config: " + extraKeys);
    }
  }

  private static String validateRemoteUrl(JsonNode urlNode) {
    if (urlNode == null || !urlNode.isTextual() || urlNode.asText().isBlank()) {
      throw new AiValidationException(RESOURCE, "url is required and must not be blank");
    }
    String url = urlNode.asText();
    String trimmed = url.strip();
    if (!url.equals(trimmed)) {
      throw new AiValidationException(RESOURCE, "url must not contain surrounding whitespace");
    }
    if (trimmed.length() > URL_MAX_LENGTH) {
      throw new AiValidationException(
          RESOURCE, "url must not exceed " + URL_MAX_LENGTH + " characters");
    }
    try {
      URI uri = new URI(trimmed);
      if (!uri.isAbsolute()) {
        throw new AiValidationException(RESOURCE, "url must be an absolute http or https URL");
      }
      String scheme = uri.getScheme();
      if (scheme == null
          || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
        throw new AiValidationException(RESOURCE, "url scheme must be http or https");
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        throw new AiValidationException(RESOURCE, "url must contain a valid host");
      }
      if (uri.getUserInfo() != null || uri.getRawUserInfo() != null) {
        throw new AiValidationException(RESOURCE, "url must not contain user-info credentials");
      }
    } catch (URISyntaxException error) {
      throw new AiValidationException(RESOURCE, "url format is invalid");
    }
    return trimmed;
  }

  private static List<String> parseCommand(JsonNode commandNode) {
    if (commandNode == null || !commandNode.isArray() || commandNode.isEmpty()) {
      throw new AiValidationException(
          RESOURCE, "command is required and must be a non-empty array");
    }
    List<String> command = new ArrayList<>(commandNode.size());
    for (int i = 0; i < commandNode.size(); i++) {
      JsonNode item = commandNode.get(i);
      if (!item.isTextual() || item.asText().isBlank()) {
        throw new AiValidationException(
            RESOURCE, "command elements must be non-blank strings at index " + i);
      }
      command.add(item.asText());
    }
    return Collections.unmodifiableList(command);
  }

  private static String validateCwd(JsonNode cwdNode) {
    if (cwdNode == null || !cwdNode.isTextual() || cwdNode.asText().isBlank()) {
      throw new AiValidationException(RESOURCE, "cwd is required and must not be blank");
    }
    String cwd = cwdNode.asText();
    String trimmed = cwd.strip();
    if (!cwd.equals(trimmed)) {
      throw new AiValidationException(RESOURCE, "cwd must not contain surrounding whitespace");
    }
    if (trimmed.length() > CWD_MAX_LENGTH) {
      throw new AiValidationException(
          RESOURCE, "cwd must not exceed " + CWD_MAX_LENGTH + " characters");
    }
    if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
      throw new AiValidationException(RESOURCE, "cwd must not contain control characters");
    }
    if (!isTargetAbsoluteCwd(trimmed)) {
      throw new AiValidationException(
          RESOURCE, "cwd must be an absolute Unix, Windows drive-rooted, or Windows UNC path");
    }
    return trimmed;
  }

  private static boolean isTargetAbsoluteCwd(String cwd) {
    if (WHOLE_VAR_PATTERN.matcher(cwd).matches()) {
      return true;
    }
    if (EMBEDDED_VAR_PATTERN.matcher(cwd).matches()) {
      return false;
    }
    if (cwd.startsWith("/")) {
      return true;
    }
    if (cwd.length() >= 3) {
      char driveLetter = cwd.charAt(0);
      char separator = cwd.charAt(2);
      boolean asciiLetter =
          (driveLetter >= 'a' && driveLetter <= 'z') || (driveLetter >= 'A' && driveLetter <= 'Z');
      if (asciiLetter && cwd.charAt(1) == ':' && (separator == '/' || separator == '\\')) {
        return true;
      }
    }
    return isWindowsUncPath(cwd);
  }

  private static boolean isWindowsUncPath(String cwd) {
    if (!cwd.startsWith("\\\\")) {
      return false;
    }
    String remainder = cwd.substring(2);
    int serverEnd = nextPathSeparator(remainder, 0);
    if (serverEnd <= 0 || serverEnd == remainder.length() - 1) {
      return false;
    }
    int shareEnd = nextPathSeparator(remainder, serverEnd + 1);
    String share =
        shareEnd < 0
            ? remainder.substring(serverEnd + 1)
            : remainder.substring(serverEnd + 1, shareEnd);
    return !share.isBlank();
  }

  private static int nextPathSeparator(String value, int fromIndex) {
    for (int i = fromIndex; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '/' || ch == '\\') {
        return i;
      }
    }
    return -1;
  }

  private static Map<String, String> parseStringMap(JsonNode mapNode, String fieldName) {
    if (mapNode == null || mapNode.isNull()) {
      return Map.of();
    }
    if (!mapNode.isObject()) {
      throw new AiValidationException(RESOURCE, fieldName + " must be a JSON object");
    }
    Map<String, String> map = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> it = mapNode.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> entry = it.next();
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        throw new AiValidationException(RESOURCE, fieldName + " keys must not be blank");
      }
      JsonNode val = entry.getValue();
      if (!val.isTextual()) {
        throw new AiValidationException(
            RESOURCE, fieldName + " values must be strings: key " + key);
      }
      map.put(key, val.asText());
    }
    return Collections.unmodifiableMap(map);
  }

  private static JsonNode parseJsonTree(String json) {
    try {
      return OBJECT_MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new AiValidationException(RESOURCE, "configJson is not valid JSON");
    }
  }

  /** 解析结果载体。 */
  public record ParsedMcpConfig(
      McpConnectionType connectionType,
      UUID environmentId,
      String connectionConfigJson,
      boolean enabled,
      long timeoutMillis,
      McpConnectionConfig connectionConfig) {

    @Override
    public String toString() {
      return "ParsedMcpConfig[connectionType="
          + connectionType
          + ", environmentId="
          + environmentId
          + ", enabled="
          + enabled
          + ", timeoutMillis="
          + timeoutMillis
          + ", connectionConfig=<redacted>]";
    }
  }
}

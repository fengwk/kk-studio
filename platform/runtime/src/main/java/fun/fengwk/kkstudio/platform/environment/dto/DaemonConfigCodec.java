package fun.fengwk.kkstudio.platform.environment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 卡片 Daemon 配置的唯一结构与取值校验边界。
 *
 * <p>错误只包含字段路径与规则，不包含输入值或解析器异常。parse 严格校验 JSON 的实际类型且拒绝未知字段；validate 同样校验程序构造的模型，返回独立、
 * 规范化的完整配置。
 */
public final class DaemonConfigCodec {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final ObjectMapper CANONICAL =
      JsonMapper.builder()
          .changeDefaultPropertyInclusion(
              inclusion -> inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
          .build();

  private DaemonConfigCodec() {}

  /** 从持久化或本地信封中的 {@code daemonConfig} 对象读取配置。 */
  public static DaemonConfigDTO read(String json) {
    if (json == null) {
      throw new IllegalArgumentException("daemonConfig: must be an object");
    }
    JsonNode node;
    try {
      node = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new IllegalArgumentException(
          "daemonConfig: must be valid JSON without duplicate keys or trailing data");
    }
    return parse(node);
  }

  public static DaemonConfigDTO parse(JsonNode node) {
    object(node, "daemonConfig", Set.of("studioUrl", "bashExecutable", "lsp", "proxyUrl"));
    DaemonConfigDTO config = new DaemonConfigDTO();
    config.setStudioUrl(text(node.get("studioUrl"), "daemonConfig.studioUrl"));
    config.setBashExecutable(text(node.get("bashExecutable"), "daemonConfig.bashExecutable"));
    JsonNode lsp = node.get("lsp");
    config.setLsp(absent(lsp) ? null : parseLspNode(lsp));
    config.setProxyUrl(text(node.get("proxyUrl"), "daemonConfig.proxyUrl"));
    return validate(config);
  }

  private static DaemonLspConfiguration parseLspNode(JsonNode node) {
    object(node, "daemonConfig.lsp", Set.of("servers"));
    JsonNode servers = node.get("servers");
    if (servers == null || !servers.isObject()) {
      throw invalid("daemonConfig.lsp.servers", "must be an object");
    }
    Map<String, DaemonLspServerConfiguration> parsed = new LinkedHashMap<>();
    servers
        .properties()
        .forEach(
            entry -> {
              String id = entry.getKey();
              serverId(id);
              String path = "daemonConfig.lsp.servers." + id;
              JsonNode server = entry.getValue();
              object(
                  server,
                  path,
                  Set.of("command", "extensions", "rootMarkers", "firstMatchMarkers"));
              DaemonLspServerConfiguration value = new DaemonLspServerConfiguration();
              value.setCommand(textList(server.get("command"), path + ".command"));
              value.setExtensions(textList(server.get("extensions"), path + ".extensions"));
              value.setRootMarkers(textList(server.get("rootMarkers"), path + ".rootMarkers"));
              value.setFirstMatchMarkers(
                  textList(server.get("firstMatchMarkers"), path + ".firstMatchMarkers"));
              parsed.put(id, value);
            });
    DaemonLspConfiguration config = new DaemonLspConfiguration();
    config.setServers(parsed);
    return config;
  }

  public static DaemonConfigDTO validate(DaemonConfigDTO config) {
    if (config == null) {
      throw invalid("daemonConfig", "must be an object");
    }
    DaemonConfigDTO result = new DaemonConfigDTO();
    result.setStudioUrl(origin(config.getStudioUrl()).toString());
    result.setBashExecutable(optionalNonblank(config.getBashExecutable()));
    result.setLsp(config.getLsp() == null ? null : validateLsp(config.getLsp()));
    result.setProxyUrl(proxy(config.getProxyUrl()));
    return result;
  }

  private static String optionalNonblank(String value) {
    if (value == null) {
      return null;
    }
    nonblank(value, "daemonConfig.bashExecutable");
    return value;
  }

  private static String proxy(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String path = "daemonConfig.proxyUrl";
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException error) {
      throw invalid(path, "must be an unauthenticated HTTP proxy origin");
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || !scheme.equalsIgnoreCase("http")
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || (uri.getRawPath() != null
            && !uri.getRawPath().isEmpty()
            && !uri.getRawPath().equals("/"))
        || uri.getPort() < 1
        || uri.getPort() > 65535) {
      throw invalid(
          path, "must be an unauthenticated http://host:port proxy without path or query");
    }
    return "http://" + uri.getRawAuthority();
  }

  private static URI origin(String value) {
    String path = "daemonConfig.studioUrl";
    nonblank(value, path);
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException error) {
      throw invalid(path, "must be an HTTP(S) origin");
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || (uri.getRawPath() != null
            && !uri.getRawPath().isEmpty()
            && !uri.getRawPath().equals("/"))) {
      throw invalid(path, "must be an HTTP(S) origin without userinfo, path, query or fragment");
    }
    String authority = uri.getRawAuthority();
    String port =
        authority.substring(
            authority.startsWith("[") ? authority.indexOf(']') + 1 : uri.getHost().length());
    if (!port.isEmpty()
        && (!port.matches(":[0-9]+") || uri.getPort() < 1 || uri.getPort() > 65535)) {
      throw invalid(path, "must have a valid authority and port");
    }
    return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + authority);
  }

  private static DaemonLspConfiguration validateLsp(DaemonLspConfiguration config) {
    Map<String, DaemonLspServerConfiguration> servers = config.getServers();
    if (servers == null || servers.isEmpty()) {
      throw invalid("daemonConfig.lsp.servers", "must declare at least one server");
    }
    Map<String, DaemonLspServerConfiguration> result = new LinkedHashMap<>();
    servers.forEach(
        (id, server) -> {
          serverId(id);
          String path = "daemonConfig.lsp.servers." + id;
          if (server == null) {
            throw invalid(path, "must be an object");
          }
          DaemonLspServerConfiguration value = new DaemonLspServerConfiguration();
          value.setCommand(list(server.getCommand(), path + ".command", true, false, false));
          value.setExtensions(
              list(server.getExtensions(), path + ".extensions", false, true, false));
          value.setRootMarkers(
              list(server.getRootMarkers(), path + ".rootMarkers", false, false, true));
          value.setFirstMatchMarkers(
              list(server.getFirstMatchMarkers(), path + ".firstMatchMarkers", false, false, true));
          result.put(id, value);
        });
    DaemonLspConfiguration validated = new DaemonLspConfiguration();
    validated.setServers(result);
    return validated;
  }

  private static List<String> list(
      List<String> values, String path, boolean required, boolean extension, boolean marker) {
    if (values == null || values.isEmpty()) {
      if (required) {
        throw invalid(path, "must be a nonempty array of strings");
      }
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (int index = 0; index < values.size(); index++) {
      String field = path + "[" + index + "]";
      String value = values.get(index);
      nonblank(value, field);
      if (extension) {
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() < 2
            || !value.startsWith(".")
            || value.contains("/")
            || value.contains("\\")) {
          throw invalid(field, "must be a file extension with a leading dot and no separators");
        }
      }
      if (marker) {
        value = value.trim();
        if (value.startsWith("/")
            || value.startsWith("\\")
            || value.contains(":")
            || List.of(value.replace('\\', '/').split("/", -1)).stream()
                .anyMatch(part -> part.equals("..") || part.equals(".") || part.isEmpty())) {
          throw invalid(field, "must be project-relative without traversal");
        }
      }
      result.add(value);
    }
    return List.copyOf(result);
  }

  private static void serverId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_.-]+")) {
      throw invalid("daemonConfig.lsp.servers", "server IDs must match [A-Za-z0-9_.-]+");
    }
  }

  /** 按完整 canonical 结构写入持久化 JSON，显式的 null 表示直连或未配置。 */
  public static String write(DaemonConfigDTO config) {
    try {
      return CANONICAL.writeValueAsString(validate(config));
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot serialize daemonConfig", error);
    }
  }

  /** 按完整 canonical 结构生成 JSON 树，供安装脚本写入本地信封。 */
  public static JsonNode tree(DaemonConfigDTO config) {
    return CANONICAL.valueToTree(validate(config));
  }

  private static void object(JsonNode node, String path, Set<String> fields) {
    if (node == null || !node.isObject()) {
      throw invalid(path, "must be an object");
    }
    node.propertyNames()
        .forEach(
            field -> {
              if (!fields.contains(field)) {
                String suffix = field.matches("[A-Za-z0-9_.-]+") ? "." + field : "";
                throw invalid(path + suffix, "unknown field");
              }
            });
  }

  private static String text(JsonNode node, String path) {
    if (absent(node)) {
      return null;
    }
    if (!node.isString()) {
      throw invalid(path, "must be a string");
    }
    return node.stringValue();
  }

  private static List<String> textList(JsonNode node, String path) {
    if (absent(node)) {
      return null;
    }
    if (!node.isArray()) {
      throw invalid(path, "must be an array of strings");
    }
    List<String> values = new ArrayList<>();
    for (int index = 0; index < node.size(); index++) {
      values.add(text(node.get(index), path + "[" + index + "]"));
    }
    return values;
  }

  private static boolean absent(JsonNode node) {
    return node == null || node.isNull();
  }

  private static void nonblank(String value, String path) {
    if (value == null || value.isBlank()) {
      throw invalid(path, "must be a nonblank string");
    }
    noControls(value, path);
  }

  private static void noControls(String value, String path) {
    if (value.codePoints().anyMatch(Character::isISOControl)
        || value.indexOf('\u2028') >= 0
        || value.indexOf('\u2029') >= 0) {
      throw invalid(path, "must not contain control characters");
    }
  }

  private static IllegalArgumentException invalid(String path, String rule) {
    return new IllegalArgumentException(path + ": " + rule);
  }
}

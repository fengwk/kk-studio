package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配置的唯一结构与取值校验边界。错误只包含字段路径与规则，不包含输入值或解析器异常。
 *
 * <p>parse 校验 JSON 的实际类型（不做 Jackson 标量强制转换），validate 同样校验程序构造的模型， 返回独立、规范化的配置。文件读取额外拒绝重复键与尾随 JSON。
 */
public final class DaemonConfigurationCodec {
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private DaemonConfigurationCodec() {}

  /** 读取配置文件；语法错误与 IO 错误不泄露文件内容。 */
  public static DaemonConfiguration read(Path file) {
    JsonNode node;
    try {
      node = MAPPER.readTree(Files.readAllBytes(file));
    } catch (IOException error) {
      throw invalid("daemon", "cannot read valid JSON (duplicate keys are forbidden)");
    }
    return parse(node);
  }

  public static DaemonConfiguration parse(JsonNode node) {
    object(node, "daemon", Set.of("studioUrl", "note", "bashExecutable", "lsp"));
    DaemonConfiguration config = new DaemonConfiguration();
    config.setStudioUrl(text(node.get("studioUrl"), "daemon.studioUrl"));
    config.setNote(text(node.get("note"), "daemon.note"));
    config.setBashExecutable(text(node.get("bashExecutable"), "daemon.bashExecutable"));
    JsonNode lsp = node.get("lsp");
    config.setLsp(absent(lsp) ? null : parseLsp(lsp));
    return validate(config);
  }

  public static DaemonLspConfiguration parseLsp(JsonNode node) {
    object(node, "daemon.lsp", Set.of("servers"));
    JsonNode servers = node.get("servers");
    if (servers == null || !servers.isObject()) {
      throw invalid("daemon.lsp.servers", "must be an object");
    }
    Map<String, DaemonLspServerConfiguration> parsed = new LinkedHashMap<>();
    servers
        .properties()
        .forEach(
            entry -> {
              String id = entry.getKey();
              serverId(id);
              String path = "daemon.lsp.servers." + id;
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
    return validateLsp(config);
  }

  public static DaemonConfiguration validate(DaemonConfiguration config) {
    if (config == null) {
      throw invalid("daemon", "must be an object");
    }
    DaemonConfiguration result = new DaemonConfiguration();
    result.setStudioUrl(origin(config.getStudioUrl()).toString());
    String note = config.getNote();
    if (note != null) {
      noControls(note, "daemon.note");
      note = note.trim();
      if (note.isEmpty() || note.length() > 512) {
        throw invalid("daemon.note", "must be a nonblank single line of at most 512 characters");
      }
    }
    result.setNote(note);
    String bash = config.getBashExecutable();
    if (bash != null) {
      nonblank(bash, "daemon.bashExecutable");
    }
    result.setBashExecutable(bash);
    result.setLsp(config.getLsp() == null ? null : validateLsp(config.getLsp()));
    return result;
  }

  /** Studio HTTP(S) origin 对应的 Environment WebSocket 入口。 */
  public static URI gatewayUri(String studioUrl) {
    URI origin = origin(studioUrl);
    return URI.create(
        ("https".equals(origin.getScheme()) ? "wss" : "ws")
            + "://"
            + origin.getRawAuthority()
            + "/api/harness/environment-daemon/v1");
  }

  private static URI origin(String value) {
    String path = "daemon.studioUrl";
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
      throw invalid("daemon.lsp.servers", "must declare at least one server");
    }
    Map<String, DaemonLspServerConfiguration> result = new LinkedHashMap<>();
    servers.forEach(
        (id, server) -> {
          serverId(id);
          String path = "daemon.lsp.servers." + id;
          if (server == null) {
            throw invalid(path, "must be an object");
          }
          DaemonLspServerConfiguration value = new DaemonLspServerConfiguration();
          value.setCommand(list(server.getCommand(), path + ".command", true, false, false));
          value.setExtensions(
              list(server.getExtensions(), path + ".extensions", true, true, false));
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
      throw invalid("daemon.lsp.servers", "server IDs must match [A-Za-z0-9_.-]+");
    }
  }

  private static void object(JsonNode node, String path, Set<String> fields) {
    if (node == null || !node.isObject()) {
      throw invalid(path, "must be an object");
    }
    node.fieldNames()
        .forEachRemaining(
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
    if (!node.isTextual()) {
      throw invalid(path, "must be a string");
    }
    return node.textValue();
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

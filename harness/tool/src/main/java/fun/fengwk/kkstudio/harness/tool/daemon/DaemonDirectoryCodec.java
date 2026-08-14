package fun.fengwk.kkstudio.harness.tool.daemon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentWorkspacePath;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Daemon 目录浏览请求/响应 payload 的严格 codec。
 *
 * <p>消息配对：
 *
 * <ul>
 *   <li>{@link DaemonMessageType#LIST_DIRECTORY} → {@link ListDirectoryRequest}
 *   <li>{@link DaemonMessageType#DIRECTORY_LISTED} → {@link DirectoryListed}
 *   <li>{@link DaemonMessageType#DIRECTORY_LIST_FAILED} → {@link DirectoryListFailed}
 * </ul>
 *
 * <p>目录控制面不属于 invocation：三类消息的 envelope {@code invocationId} 必须为 null，gateway/daemon 以 payload
 * {@code requestId}（canonical UUID，响应原样回显）关联。{@code path} 是 Environment Root 下的 canonical 相对 wire
 * 路径（{@code '.'} 表示 root，段一律以 {@code '/'} 分隔）：跨平台拒绝反斜杠、Windows drive 前缀（{@code C:/x}、{@code
 * C:x}）、absolute、空/{@code '.'}/{@code '..'} 段、控制字符与空白路径；越界判定由 daemon 在 canonicalize 时完成。成功响应中
 * {@code path}/{@code parentPath}/entry {@code path} 都是请求目录或其直接子目录的 canonical wire 路径，entry 只含
 * {@code name}+{@code path} 两个字段且 {@code name} 必须等于 {@code path} 的最后一段；{@code displayPath} 必须等于请求
 * {@code path} 的最后一段（root 为 {@code '.'}），codec 层严格拒绝任何其它值（含旧/恶意 daemon 泄漏的本地绝对路径）。
 *
 * <p>共享 ObjectMapper 启用 STRICT_DUPLICATE_DETECTION 与 FAIL_ON_TRAILING_TOKENS：三类 payload 顶层与 entry 的
 * duplicate/trailing/unknown/missing 字段全部拒绝。
 */
public final class DaemonDirectoryCodec {

  /** 单层目录列表的条目数上限；超过时截断并置 {@code truncated=true}。 */
  public static final int MAX_ENTRIES = 1000;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /**
   * LIST_DIRECTORY payload 的未校验形状读取结果：requestId 必须是 canonical UUID，path 只要求非空文本（供 daemon 归因非法路径）。
   */
  public record RawListDirectoryRequest(String requestId, String path) {
    public RawListDirectoryRequest {
      requestId = requireCanonicalUuid(requestId);
      path = requireNonBlank(path, "path");
    }
  }

  /** Gateway 请求浏览 Environment Root 下单层目录；{@code requestId} 是 gateway 生成的 canonical UUID。 */
  public record ListDirectoryRequest(String requestId, String path) {
    public ListDirectoryRequest {
      requestId = requireCanonicalUuid(requestId);
      path = requireCanonicalRelativePath(path);
    }
  }

  /** 列表中的单个目录条目：{@code name} 是目录名（{@code path} 的最后一段），{@code path} 是当前列表目录的直接子路径。 */
  public record DirectoryEntry(String name, String path) {
    public DirectoryEntry {
      name = requireNonBlank(name, "name");
      path = requireCanonicalRelativePath(path);
      if (!name.equals(lastSegment(path))) {
        throw new IllegalArgumentException("name must be the last segment of path: " + path);
      }
    }
  }

  /**
   * Daemon 成功返回的一层目录列表；{@code requestId} 是请求回显，{@code displayPath} 是请求 {@code path} 的最后一段（root 为
   * {@code '.'}，绝不暴露 daemon 本地绝对路径），任何其它值都拒绝。
   */
  public record DirectoryListed(
      String requestId,
      String path,
      String displayPath,
      String parentPath,
      boolean truncated,
      String gitBranch,
      List<DirectoryEntry> entries) {
    public DirectoryListed {
      requestId = requireCanonicalUuid(requestId);
      path = requireCanonicalRelativePath(path);
      displayPath = requireNonBlank(displayPath, "displayPath");
      if (!displayPath.equals(lastSegment(path))) {
        throw new IllegalArgumentException("displayPath must be the last segment of path: " + path);
      }
      parentPath = requireCanonicalRelativePath(parentPath);
      entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
      for (DirectoryEntry entry : entries) {
        requireDirectChild(path, entry);
      }
    }
  }

  /**
   * Daemon 确定性失败（非法路径、不存在、非目录、IO 错误）。{@code requestId} 是请求回显（canonical UUID）；{@code path}
   * 是请求回显，只要求非空 （非法请求路径也必须可归因）。
   */
  public record DirectoryListFailed(
      String requestId, String path, DaemonDirectoryFailureCode code, String message) {
    public DirectoryListFailed {
      requestId = requireCanonicalUuid(requestId);
      path = requireNonBlank(path, "path");
      code = Objects.requireNonNull(code, "code");
      message = requireNonBlank(message, "message");
    }
  }

  /**
   * 校验 canonical 相对 wire 路径并原样返回；{@code '.'} 单独出现表示 root，其余位置拒绝 {@code '.'}/{@code '..'} 段。
   *
   * <p>规则与 {@link EnvironmentBinding} 的 workspace path 共用同一 validator（{@link
   * fun.fengwk.kkstudio.harness.tool.EnvironmentWorkspacePath}），避免两套路径语义。
   */
  public static String requireCanonicalRelativePath(String path) {
    return EnvironmentWorkspacePath.requireCanonicalRelativePath(path);
  }

  public String encodeRequest(ListDirectoryRequest request) {
    Objects.requireNonNull(request, "request");
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("requestId", request.requestId());
    root.put("path", request.path());
    return write(root, "LIST_DIRECTORY");
  }

  public ListDirectoryRequest decodeRequest(String json) {
    RawListDirectoryRequest raw = readRequest(json);
    return new ListDirectoryRequest(raw.requestId(), raw.path());
  }

  /**
   * 读取并校验 LIST_DIRECTORY payload 的 requestId/path 文本（不构造 record、不校验 path 形状，供 daemon 对形状非法路径做错误归因）。
   */
  public RawListDirectoryRequest readRequest(String json) {
    ObjectNode root = requiredObject(json, "LIST_DIRECTORY");
    rejectUnknownFields(root, Set.of("requestId", "path"), "LIST_DIRECTORY");
    String requestId = requiredText(root, "requestId", "LIST_DIRECTORY");
    String path = requiredText(root, "path", "LIST_DIRECTORY");
    try {
      return new RawListDirectoryRequest(requestId, path);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "LIST_DIRECTORY validation failed: " + error.getMessage(), error);
    }
  }

  public String encodeListed(DirectoryListed listed) {
    Objects.requireNonNull(listed, "listed");
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("requestId", listed.requestId());
    root.put("path", listed.path());
    root.put("displayPath", listed.displayPath());
    root.put("parentPath", listed.parentPath());
    root.put("truncated", listed.truncated());
    if (listed.gitBranch() != null) {
      root.put("gitBranch", listed.gitBranch());
    }
    ArrayNode entries = root.putArray("entries");
    for (DirectoryEntry entry : listed.entries()) {
      ObjectNode node = entries.addObject();
      node.put("name", entry.name());
      node.put("path", entry.path());
    }
    return write(root, "DIRECTORY_LISTED");
  }

  public DirectoryListed decodeListed(String json) {
    ObjectNode root = requiredObject(json, "DIRECTORY_LISTED");
    rejectUnknownFields(
        root,
        Set.of(
            "requestId", "path", "displayPath", "parentPath", "truncated", "gitBranch", "entries"),
        "DIRECTORY_LISTED");
    JsonNode truncated = root.get("truncated");
    if (truncated == null || !truncated.isBoolean()) {
      throw new DaemonProtocolException("DIRECTORY_LISTED 'truncated' must be a boolean");
    }
    List<DirectoryEntry> entries = decodeEntries(requiredArray(root, "entries"));
    try {
      return new DirectoryListed(
          requiredText(root, "requestId", "DIRECTORY_LISTED"),
          requiredText(root, "path", "DIRECTORY_LISTED"),
          requiredText(root, "displayPath", "DIRECTORY_LISTED"),
          requiredText(root, "parentPath", "DIRECTORY_LISTED"),
          truncated.booleanValue(),
          optionalText(root, "gitBranch", "DIRECTORY_LISTED"),
          entries);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "DIRECTORY_LISTED validation failed: " + error.getMessage(), error);
    }
  }

  public String encodeFailed(DirectoryListFailed failed) {
    Objects.requireNonNull(failed, "failed");
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("requestId", failed.requestId());
    root.put("path", failed.path());
    root.put("code", failed.code().name());
    root.put("message", failed.message());
    return write(root, "DIRECTORY_LIST_FAILED");
  }

  public DirectoryListFailed decodeFailed(String json) {
    ObjectNode root = requiredObject(json, "DIRECTORY_LIST_FAILED");
    rejectUnknownFields(
        root, Set.of("requestId", "path", "code", "message"), "DIRECTORY_LIST_FAILED");
    String codeText = requiredText(root, "code", "DIRECTORY_LIST_FAILED");
    DaemonDirectoryFailureCode code;
    try {
      code = DaemonDirectoryFailureCode.valueOf(codeText);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "DIRECTORY_LIST_FAILED 'code' is unknown: " + codeText, error);
    }
    try {
      return new DirectoryListFailed(
          requiredText(root, "requestId", "DIRECTORY_LIST_FAILED"),
          requiredText(root, "path", "DIRECTORY_LIST_FAILED"),
          code,
          requiredText(root, "message", "DIRECTORY_LIST_FAILED"));
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "DIRECTORY_LIST_FAILED validation failed: " + error.getMessage(), error);
    }
  }

  private static List<DirectoryEntry> decodeEntries(JsonNode entriesNode) {
    List<DirectoryEntry> result = new ArrayList<>();
    int index = 0;
    for (JsonNode element : entriesNode) {
      if (!(element instanceof ObjectNode node)) {
        throw new DaemonProtocolException(
            "DIRECTORY_LISTED entries[" + index + "] must be an object");
      }
      rejectUnknownFields(node, Set.of("name", "path"), "DIRECTORY_LISTED entries[" + index + "]");
      try {
        result.add(
            new DirectoryEntry(
                requiredText(node, "name", "DIRECTORY_LISTED entries[" + index + "]"),
                requiredText(node, "path", "DIRECTORY_LISTED entries[" + index + "]")));
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(
            "DIRECTORY_LISTED entry validation failed: " + error.getMessage(), error);
      }
      index++;
    }
    return List.copyOf(result);
  }

  /** 校验 {@code entry} 的 {@code path} 是 {@code parentPath} 的直接子路径（root 请求时是单段路径）。 */
  private static void requireDirectChild(String parentPath, DirectoryEntry entry) {
    String prefix = ".".equals(parentPath) ? "" : parentPath + "/";
    String childPath = entry.path();
    if (!childPath.startsWith(prefix)) {
      throw new IllegalArgumentException(
          "entry path must be a direct child of " + parentPath + ": " + childPath);
    }
    String remainder = childPath.substring(prefix.length());
    if (remainder.isEmpty() || remainder.indexOf('/') >= 0) {
      throw new IllegalArgumentException(
          "entry path must be a direct child of " + parentPath + ": " + childPath);
    }
  }

  private static String lastSegment(String path) {
    int separator = path.lastIndexOf('/');
    return separator < 0 ? path : path.substring(separator + 1);
  }

  private static String write(ObjectNode root, String context) {
    try {
      return OBJECT_MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode " + context + " payload", error);
    }
  }

  private static ObjectNode requiredObject(String json, String context) {
    if (json == null) {
      throw new DaemonProtocolException(context + " payload must not be null");
    }
    try {
      JsonNode value = OBJECT_MAPPER.readTree(json);
      if (value == null || !value.isObject()) {
        throw new DaemonProtocolException(context + " payload must be a JSON object");
      }
      return (ObjectNode) value;
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException(context + " payload must be valid JSON", error);
    }
  }

  private static JsonNode requiredArray(ObjectNode root, String fieldName) {
    JsonNode node = root.get(fieldName);
    if (node == null || !node.isArray()) {
      throw new DaemonProtocolException("DIRECTORY_LISTED '" + fieldName + "' must be an array");
    }
    return node;
  }

  private static void rejectUnknownFields(ObjectNode root, Set<String> allowed, String context) {
    Iterator<String> fields = root.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!allowed.contains(field)) {
        throw new DaemonProtocolException(context + " has unknown field: " + field);
      }
    }
  }

  private static String requiredText(ObjectNode root, String fieldName, String context) {
    String value = optionalText(root, fieldName, context);
    if (value == null) {
      throw new DaemonProtocolException(context + " '" + fieldName + "' is required");
    }
    return value;
  }

  private static String optionalText(ObjectNode root, String fieldName, String context) {
    JsonNode value = root.get(fieldName);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(
          context + " '" + fieldName + "' must be a non-blank string");
    }
    return value.textValue();
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  /** 校验 requestId 是 canonical UUID 文本并原样返回。 */
  private static String requireCanonicalUuid(String value) {
    String text = requireNonBlank(value, "requestId");
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) {
        throw new IllegalArgumentException("requestId must be a canonical UUID string: " + text);
      }
      return text;
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "requestId must be a canonical UUID string: " + text, error);
    }
  }
}

package fun.fengwk.kkstudio.harness.tool.daemon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Daemon v1 Skill 加载请求/响应 payload 的严格 codec。
 *
 * <p>消息配对：
 *
 * <ul>
 *   <li>{@link DaemonMessageType#LOAD_SKILL} → {@link LoadSkillRequest}
 *   <li>{@link DaemonMessageType#SKILL_LOADED} → {@link SkillLoaded}
 *   <li>{@link DaemonMessageType#SKILL_LOAD_FAILED} → {@link SkillLoadFailed}
 * </ul>
 *
 * <p>三类消息均要求 envelope {@code invocationId} 作为 gateway 与 daemon 的关联 ID。
 */
public final class DaemonSkillLoadCodec {

  /** Gateway 请求按 name 加载完整 SKILL.md 正文。 */
  public record LoadSkillRequest(String name) {
    public LoadSkillRequest {
      name = requireNonBlank(name, "name");
    }
  }

  /** Daemon 成功返回完整 SKILL.md 正文。 */
  public record SkillLoaded(String name, String content) {
    public SkillLoaded {
      name = requireNonBlank(name, "name");
      content = Objects.requireNonNull(content, "content");
    }
  }

  /** Daemon 确定性失败（未知 skill、读取失败等）。 */
  public record SkillLoadFailed(String name, String message) {
    public SkillLoadFailed {
      name = requireNonBlank(name, "name");
      message = requireNonBlank(message, "message");
    }
  }

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public String encodeRequest(LoadSkillRequest request) {
    Objects.requireNonNull(request, "request");
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("name", request.name());
    return write(root, "LOAD_SKILL");
  }

  public LoadSkillRequest decodeRequest(String json) {
    ObjectNode root = requiredObject(json, "LOAD_SKILL");
    rejectUnknownFields(root, Set.of("name"), "LOAD_SKILL");
    return new LoadSkillRequest(requiredText(root, "name", "LOAD_SKILL"));
  }

  public String encodeLoaded(SkillLoaded loaded) {
    Objects.requireNonNull(loaded, "loaded");
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("name", loaded.name());
    root.put("content", loaded.content());
    return write(root, "SKILL_LOADED");
  }

  public SkillLoaded decodeLoaded(String json) {
    ObjectNode root = requiredObject(json, "SKILL_LOADED");
    rejectUnknownFields(root, Set.of("name", "content"), "SKILL_LOADED");
    return new SkillLoaded(
        requiredText(root, "name", "SKILL_LOADED"), requiredText(root, "content", "SKILL_LOADED"));
  }

  public String encodeFailed(SkillLoadFailed failed) {
    Objects.requireNonNull(failed, "failed");
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("name", failed.name());
    root.put("message", failed.message());
    return write(root, "SKILL_LOAD_FAILED");
  }

  public SkillLoadFailed decodeFailed(String json) {
    ObjectNode root = requiredObject(json, "SKILL_LOAD_FAILED");
    rejectUnknownFields(root, Set.of("name", "message"), "SKILL_LOAD_FAILED");
    return new SkillLoadFailed(
        requiredText(root, "name", "SKILL_LOAD_FAILED"),
        requiredText(root, "message", "SKILL_LOAD_FAILED"));
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
    JsonNode value = root.get(fieldName);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(
          context + " '" + fieldName + "' must be a non-blank string");
    }
    return value.textValue();
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}

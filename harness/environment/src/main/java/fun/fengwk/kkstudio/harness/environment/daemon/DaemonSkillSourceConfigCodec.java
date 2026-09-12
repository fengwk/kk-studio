package fun.fengwk.kkstudio.harness.environment.daemon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@link DaemonSkillSourceConfig} 的严格、确定性 JSON codec。
 *
 * <p>wire 只包含当前来源类型的字段：类型无关字段（{@code sourceId}/{@code sourceVersion}/{@code
 * sourceSetVersion}/{@code type}/{@code activeSourceIds}）恒出现，类型专属的可空字段仅在存在时出现，因此同一配置的 wire 文本
 * canonical 且不携带无意义的显式 null。解码接受字段缺省或显式 null（归一为缺省），但拒绝未知字段、重复键、尾随内容、错误类型与自相矛盾的配置（PATH 带 GIT 字段等）。
 * 异常文本只报告字段名与结构性问题，不回显 url/ref/path 原值。
 */
public final class DaemonSkillSourceConfigCodec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final Set<String> FIELDS =
      Set.of(
          "sourceId",
          "sourceVersion",
          "sourceSetVersion",
          "type",
          "path",
          "defaultSource",
          "url",
          "ref",
          "scanPath",
          "currentlyAppliedRevision",
          "activeSourceIds");

  public String encode(DaemonSkillSourceConfig config) {
    Objects.requireNonNull(config, "config");
    ObjectNode node = MAPPER.createObjectNode();
    node.put("sourceId", config.sourceId().toString());
    node.put("sourceVersion", config.sourceVersion());
    node.put("sourceSetVersion", config.sourceSetVersion());
    node.put("type", config.type().wireValue());
    putPresent(node, "path", config.path());
    node.put("defaultSource", config.defaultSource());
    putPresent(node, "url", config.url());
    putPresent(node, "ref", config.ref());
    putPresent(node, "scanPath", config.scanPath());
    putPresent(node, "currentlyAppliedRevision", config.currentlyAppliedRevision());
    ArrayNode activeSourceIds = node.putArray("activeSourceIds");
    config.activeSourceIds().stream()
        .sorted()
        .forEach(sourceId -> activeSourceIds.add(sourceId.toString()));
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode Skill source config", error);
    }
  }

  public DaemonSkillSourceConfig decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("malformed Skill source config", error);
    }
    if (!(value instanceof ObjectNode node)) {
      throw new DaemonProtocolException("Skill source config must be an object");
    }
    node.fieldNames()
        .forEachRemaining(
            field -> {
              if (!FIELDS.contains(field)) {
                throw new DaemonProtocolException("unexpected Skill source config field: " + field);
              }
            });
    UUID sourceId = requiredSourceId(node, "sourceId");
    long sourceVersion = requiredNonNegativeLong(node, "sourceVersion");
    long sourceSetVersion = requiredNonNegativeLong(node, "sourceSetVersion");
    String typeText = requiredText(node, "type");
    DaemonSkillSourceType type;
    try {
      type = DaemonSkillSourceType.fromWireValue(typeText);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("Skill source config.type has unknown value", error);
    }
    boolean defaultSource = optionalBoolean(node, "defaultSource");
    Set<UUID> activeSourceIds = requiredSourceIds(node, "activeSourceIds");
    try {
      return new DaemonSkillSourceConfig(
          sourceId,
          sourceVersion,
          sourceSetVersion,
          type,
          optionalText(node, "path"),
          defaultSource,
          optionalText(node, "url"),
          optionalText(node, "ref"),
          optionalText(node, "scanPath"),
          optionalText(node, "currentlyAppliedRevision"),
          activeSourceIds);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "Skill source config validation failed: " + error.getMessage(), error);
    }
  }

  /** 写入可选文本字段：缺省时不出现该字段，避免 canonical JSON 携带无意义的显式 null。 */
  private static void putPresent(ObjectNode node, String field, String value) {
    if (value != null) {
      node.put(field, value);
    }
  }

  private static UUID requiredSourceId(ObjectNode node, String field) {
    String value = requiredText(node, field);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw new IllegalArgumentException("not canonical");
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "Skill source config." + field + " must be a canonical UUID string", error);
    }
  }

  private static Set<UUID> requiredSourceIds(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray() || value.isEmpty()) {
      throw new DaemonProtocolException(
          "Skill source config." + field + " must be a non-empty array");
    }
    if (value.size() > DaemonSkillSourceConfig.MAX_ACTIVE_SOURCES) {
      throw new DaemonProtocolException(
          "Skill source config."
              + field
              + " must not exceed "
              + DaemonSkillSourceConfig.MAX_ACTIVE_SOURCES
              + " entries");
    }
    Set<UUID> result = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.textValue().isBlank()) {
        throw new DaemonProtocolException(
            "Skill source config." + field + " entries must be canonical UUID strings");
      }
      String text = element.textValue();
      UUID parsed;
      try {
        parsed = UUID.fromString(text);
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(
            "Skill source config." + field + " entries must be canonical UUID strings", error);
      }
      if (!parsed.toString().equals(text) || !result.add(parsed)) {
        throw new DaemonProtocolException(
            "Skill source config." + field + " entries must be unique canonical UUID strings");
      }
    }
    return Set.copyOf(result);
  }

  private static long requiredNonNegativeLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) {
      throw new DaemonProtocolException(
          "Skill source config." + field + " must be a non-negative long integer");
    }
    return value.longValue();
  }

  private static boolean optionalBoolean(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return false;
    }
    if (!value.isBoolean()) {
      throw new DaemonProtocolException("Skill source config." + field + " must be boolean");
    }
    return value.booleanValue();
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException("Skill source config." + field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static String optionalText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    return requiredText(node, field);
  }
}

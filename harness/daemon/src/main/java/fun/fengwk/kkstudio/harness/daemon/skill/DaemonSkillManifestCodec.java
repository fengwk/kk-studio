package fun.fengwk.kkstudio.harness.daemon.skill;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillManifest.RetainedSkill;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshotCodec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 本地 Skill manifest 的严格 JSON codec。
 *
 * <p>manifest 是 Daemon 重启后恢复当前来源快照与不可变历史索引的唯一事实源；它只保存文本字段，正文由内容 revision 指向的 blob
 * 提供。解码拒绝未知字段、缺失字段与错误类型，损坏的 manifest 必须显式失败而不是静默降级为空目录。
 */
final class DaemonSkillManifestCodec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final Set<String> ROOT_FIELDS =
      Set.of("version", "sourceSetVersion", "activeSourceIds", "sources", "retained");
  private static final Set<String> RETAINED_FIELDS =
      Set.of("sourceId", "name", "revision", "baseDirectory");

  private static final DaemonSkillSourceSnapshotCodec SNAPSHOT_CODEC =
      new DaemonSkillSourceSnapshotCodec();

  private DaemonSkillManifestCodec() {}

  static String encode(DaemonSkillManifest manifest) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("version", manifest.version());
    root.put("sourceSetVersion", manifest.sourceSetVersion());
    ArrayNode activeSourceIds = root.putArray("activeSourceIds");
    for (UUID sourceId : manifest.activeSourceIds()) {
      activeSourceIds.add(sourceId.toString());
    }
    ArrayNode sources = root.putArray("sources");
    for (DaemonSkillSourceSnapshot source : manifest.sources()) {
      sources.add(SNAPSHOT_CODEC.encodeNode(source));
    }
    ArrayNode retained = root.putArray("retained");
    for (RetainedSkill skill : manifest.retained()) {
      ObjectNode node = retained.addObject();
      node.put("sourceId", skill.sourceId().toString());
      node.put("name", skill.name());
      node.put("revision", skill.revision());
      node.put("baseDirectory", skill.baseDirectory());
    }
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode skill manifest", error);
    }
  }

  /**
   * 解码 manifest。
   *
   * <p>所有形状与不变量失败都归一为 {@link IllegalStateException}：调用方据此 fail-closed 地拒绝恢复，绝不把损坏的清单降级为空目录。
   */
  static DaemonSkillManifest decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("malformed skill manifest JSON", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new IllegalStateException("skill manifest must be a JSON object");
    }
    try {
      return build(root);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException("invalid skill manifest: " + error.getMessage(), error);
    }
  }

  private static DaemonSkillManifest build(ObjectNode root) {
    root.fieldNames()
        .forEachRemaining(
            field -> {
              if (!ROOT_FIELDS.contains(field)) {
                throw new IllegalStateException("unexpected skill manifest field: " + field);
              }
            });
    JsonNode versionNode = root.get("version");
    if (versionNode == null || !versionNode.isIntegralNumber() || !versionNode.canConvertToInt()) {
      throw new IllegalStateException("skill manifest.version must be an integer");
    }
    return new DaemonSkillManifest(
        versionNode.intValue(),
        nonNegativeLong(root, "sourceSetVersion"),
        decodeActiveSourceIds(root),
        decodeSources(root),
        decodeRetained(root));
  }

  private static long nonNegativeLong(ObjectNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null
        || !node.isIntegralNumber()
        || !node.canConvertToLong()
        || node.longValue() < 0) {
      throw new IllegalStateException("skill manifest." + field + " must be a non-negative long");
    }
    return node.longValue();
  }

  /** 生效来源集合：canonical UUID 列表，允许为空（尚未发布任何来源）。 */
  private static List<UUID> decodeActiveSourceIds(ObjectNode root) {
    JsonNode node = root.get("activeSourceIds");
    if (node == null || !node.isArray()) {
      throw new IllegalStateException("skill manifest.activeSourceIds must be an array");
    }
    List<UUID> result = new ArrayList<>(node.size());
    Set<UUID> seen = new HashSet<>();
    for (JsonNode element : node) {
      if (!element.isTextual()) {
        throw new IllegalStateException(
            "skill manifest.activeSourceIds entries must be canonical UUID strings");
      }
      UUID parsed = canonicalUuid(element.textValue(), "skill manifest.activeSourceIds entries");
      if (!seen.add(parsed)) {
        throw new IllegalStateException("duplicate skill manifest active source: " + parsed);
      }
      result.add(parsed);
    }
    return List.copyOf(result);
  }

  private static List<DaemonSkillSourceSnapshot> decodeSources(ObjectNode root) {
    JsonNode node = root.get("sources");
    if (node == null || !node.isArray()) {
      throw new IllegalStateException("skill manifest.sources must be an array");
    }
    List<DaemonSkillSourceSnapshot> result = new ArrayList<>(node.size());
    Set<UUID> seen = new HashSet<>();
    int index = 0;
    for (JsonNode element : node) {
      DaemonSkillSourceSnapshot snapshot;
      try {
        snapshot = SNAPSHOT_CODEC.decodeNode(element, "skill manifest.sources[" + index + "]");
      } catch (DaemonProtocolException error) {
        throw new IllegalStateException(
            "invalid skill manifest source: " + error.getMessage(), error);
      }
      if (!seen.add(snapshot.sourceId())) {
        throw new IllegalStateException("duplicate skill manifest source: " + snapshot.sourceId());
      }
      result.add(snapshot);
      index++;
    }
    return List.copyOf(result);
  }

  private static List<RetainedSkill> decodeRetained(ObjectNode root) {
    JsonNode node = root.get("retained");
    if (node == null || !node.isArray()) {
      throw new IllegalStateException("skill manifest.retained must be an array");
    }
    List<RetainedSkill> result = new ArrayList<>(node.size());
    Set<String> seen = new HashSet<>();
    int index = 0;
    for (JsonNode element : node) {
      String context = "skill manifest.retained[" + index + "]";
      if (!(element instanceof ObjectNode retainedNode)) {
        throw new IllegalStateException(context + " must be an object");
      }
      retainedNode
          .fieldNames()
          .forEachRemaining(
              field -> {
                if (!RETAINED_FIELDS.contains(field)) {
                  throw new IllegalStateException("unexpected " + context + " field: " + field);
                }
              });
      RetainedSkill retained;
      try {
        retained =
            new RetainedSkill(
                uuid(retainedNode, "sourceId", context),
                text(retainedNode, "name", context),
                text(retainedNode, "revision", context),
                text(retainedNode, "baseDirectory", context));
      } catch (IllegalArgumentException error) {
        throw new IllegalStateException(context + " is invalid: " + error.getMessage(), error);
      }
      if (!seen.add(
          retained.sourceId() + "\u0000" + retained.name() + "\u0000" + retained.revision())) {
        throw new IllegalStateException("duplicate skill manifest retained entry at " + context);
      }
      result.add(retained);
      index++;
    }
    return List.copyOf(result);
  }

  /** 解析 canonical UUID 文本：非 canonical 形状（大小写、缺段、非十六进制）必须显式失败。 */
  private static UUID uuid(ObjectNode node, String field, String context) {
    return canonicalUuid(text(node, field, context), context + "." + field);
  }

  private static UUID canonicalUuid(String value, String context) {
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException(context + " must be a UUID", error);
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalStateException(context + " must be a canonical UUID string");
    }
    return parsed;
  }

  private static String text(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalStateException(context + "." + field + " must be non-blank text");
    }
    return value.textValue();
  }
}

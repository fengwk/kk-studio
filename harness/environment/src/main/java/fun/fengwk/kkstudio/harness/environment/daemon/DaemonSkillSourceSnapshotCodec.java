package fun.fengwk.kkstudio.harness.environment.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link DaemonSkillSourceSnapshot} 的严格 wire node codec。
 *
 * <p>READY 的能力报告与技能来源管理操作的结果共用同一份来源快照形状：{@code {"sourceId","sourceVersion",
 * "sourceRevision","skills":[{sourceId,sourceVersion,name,description,baseDirectory,contentRevision}],
 * "diagnostics":[{location,message}]}}。解码拒绝未知字段、缺失字段、错误类型、非 canonical UUID 与跨来源不一致的 skill 归属。
 */
public final class DaemonSkillSourceSnapshotCodec {

  private static final Set<String> DIAGNOSTIC_FIELDS = Set.of("location", "message");

  private static final Set<String> SKILL_FIELDS =
      Set.of(
          "sourceId", "sourceVersion", "name", "description", "baseDirectory", "contentRevision");

  private static final Set<String> SNAPSHOT_FIELDS =
      Set.of("sourceId", "sourceVersion", "sourceRevision", "skills", "diagnostics");

  /** 把一个来源快照写入给定 JSON object node。 */
  public ObjectNode encodeNode(DaemonSkillSourceSnapshot snapshot) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("sourceId", snapshot.sourceId().toString());
    node.put("sourceVersion", snapshot.sourceVersion());
    node.put("sourceRevision", snapshot.sourceRevision());
    ArrayNode skills = node.putArray("skills");
    for (DaemonSkillDescriptor skill : snapshot.skills()) {
      ObjectNode skillNode = skills.addObject();
      skillNode.put("sourceId", skill.sourceId().toString());
      skillNode.put("sourceVersion", skill.sourceVersion());
      skillNode.put("name", skill.name());
      skillNode.put("description", skill.description());
      skillNode.put("baseDirectory", skill.baseDirectory());
      skillNode.put("contentRevision", skill.contentRevision());
    }
    ArrayNode diagnostics = node.putArray("diagnostics");
    for (DaemonSkillDiagnostic diagnostic : snapshot.diagnostics()) {
      ObjectNode diagnosticNode = diagnostics.addObject();
      diagnosticNode.put("location", diagnostic.location());
      diagnosticNode.put("message", diagnostic.message());
    }
    return node;
  }

  /**
   * 从 wire node 解码来源快照。
   *
   * @param value 待解码 node
   * @param context 错误文本中的字段上下文
   * @throws DaemonProtocolException 形状或字段值非法时抛出
   */
  public DaemonSkillSourceSnapshot decodeNode(JsonNode value, String context) {
    if (!(value instanceof ObjectNode node)) {
      throw new DaemonProtocolException(context + " must be an object");
    }
    rejectUnknown(node, SNAPSHOT_FIELDS, context);
    UUID sourceId = requiredUuid(node, "sourceId", context);
    long sourceVersion = requiredSourceVersion(node, context);
    String sourceRevision = text(node, "sourceRevision", context);
    List<DaemonSkillDescriptor> skills = decodeSkills(node, sourceId, sourceVersion, context);
    try {
      return new DaemonSkillSourceSnapshot(
          sourceId, sourceVersion, sourceRevision, skills, decodeDiagnostics(node, context));
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          context + " validation failed: " + error.getMessage(), error);
    }
  }

  private static List<DaemonSkillDescriptor> decodeSkills(
      ObjectNode sourceNode, UUID sourceId, long sourceVersion, String context) {
    JsonNode node = sourceNode.get("skills");
    if (node == null || !node.isArray()) {
      throw new DaemonProtocolException(context + ".skills must be an array");
    }
    Set<String> names = new HashSet<>();
    List<DaemonSkillDescriptor> result = new ArrayList<>(node.size());
    int index = 0;
    for (JsonNode element : node) {
      String skillContext = context + ".skills[" + index + "]";
      if (!(element instanceof ObjectNode skillNode)) {
        throw new DaemonProtocolException(skillContext + " must be an object");
      }
      rejectUnknown(skillNode, SKILL_FIELDS, skillContext);
      UUID declaredSourceId = requiredUuid(skillNode, "sourceId", skillContext);
      long declaredSourceVersion = requiredSourceVersion(skillNode, skillContext);
      if (!declaredSourceId.equals(sourceId) || declaredSourceVersion != sourceVersion) {
        throw new DaemonProtocolException(
            skillContext + " must belong to the enclosing skill source");
      }
      String name = text(skillNode, "name", skillContext);
      if (!names.add(name)) {
        throw new DaemonProtocolException("duplicate skill name within source: " + name);
      }
      try {
        result.add(
            new DaemonSkillDescriptor(
                declaredSourceId,
                declaredSourceVersion,
                name,
                text(skillNode, "description", skillContext),
                text(skillNode, "baseDirectory", skillContext),
                text(skillNode, "contentRevision", skillContext)));
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(
            "skill validation failed for " + name + ": " + error.getMessage(), error);
      }
      index++;
    }
    return List.copyOf(result);
  }

  private static List<DaemonSkillDiagnostic> decodeDiagnostics(
      ObjectNode sourceNode, String context) {
    JsonNode node = sourceNode.get("diagnostics");
    if (node == null || !node.isArray()) {
      throw new DaemonProtocolException(context + ".diagnostics must be an array");
    }
    if (node.size() > DaemonSkillSourceSnapshot.MAX_DIAGNOSTICS) {
      throw new DaemonProtocolException(
          context
              + ".diagnostics must not exceed "
              + DaemonSkillSourceSnapshot.MAX_DIAGNOSTICS
              + " entries");
    }
    List<DaemonSkillDiagnostic> result = new ArrayList<>(node.size());
    int index = 0;
    for (JsonNode element : node) {
      String diagnosticContext = context + ".diagnostics[" + index + "]";
      if (!(element instanceof ObjectNode diagnosticNode)) {
        throw new DaemonProtocolException(diagnosticContext + " must be an object");
      }
      rejectUnknown(diagnosticNode, DIAGNOSTIC_FIELDS, diagnosticContext);
      try {
        result.add(
            new DaemonSkillDiagnostic(
                text(diagnosticNode, "location", diagnosticContext),
                text(diagnosticNode, "message", diagnosticContext)));
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(
            diagnosticContext + " validation failed: " + error.getMessage(), error);
      }
      index++;
    }
    return List.copyOf(result);
  }

  private static long requiredSourceVersion(ObjectNode node, String context) {
    JsonNode value = node.get("sourceVersion");
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) {
      throw new DaemonProtocolException(
          context + ".sourceVersion must be a non-negative long integer");
    }
    return value.longValue();
  }

  private static UUID requiredUuid(ObjectNode node, String field, String context) {
    String value = text(node, field, context);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw new IllegalArgumentException("not canonical");
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          context + "." + field + " must be a canonical UUID string", error);
    }
  }

  private static String text(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(context + "." + field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static void rejectUnknown(ObjectNode node, Set<String> expected, String context) {
    node.fieldNames()
        .forEachRemaining(
            field -> {
              if (!expected.contains(field)) {
                throw new DaemonProtocolException("unexpected " + context + " field: " + field);
              }
            });
  }
}

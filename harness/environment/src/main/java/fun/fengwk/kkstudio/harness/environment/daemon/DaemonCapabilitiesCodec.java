package fun.fengwk.kkstudio.harness.environment.daemon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Daemon READY 能力 payload 的严格 codec：版本化、类型化，拒绝未知字段、重复键、尾随内容与缺失字段。
 *
 * <p>wire shape：{@code
 * {"version":2,"environment":{...},"sourceSetVersion":0,"skillSources":[{sourceId,sourceVersion,
 * sourceRevision,skills,diagnostics}]}}。{@code sourceSetVersion} 是必填的非负顶层整数，缺失、负数或非整数都按协议错误拒绝；旧
 * shape （v1 顶层平铺 {@code skills}、无 {@code sourceSetVersion} 的 v2）被明确拒绝，不做双解码。
 */
public final class DaemonCapabilitiesCodec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final Set<String> ROOT_FIELDS =
      Set.of("version", "environment", "sourceSetVersion", "skillSources");
  private static final Set<String> ENVIRONMENT_FIELDS =
      Set.of("operatingSystem", "timeZone", "note", "rootPath");

  /** 单个 payload 内的来源数上限；与 {@link DaemonCapabilities#MAX_SOURCES} 是同一个配额。 */
  public static final int MAX_SOURCES = DaemonCapabilities.MAX_SOURCES;

  /** 单个 payload 内的 skill 描述总数上限，防止 READY 帧无界放大；与 {@link DaemonCapabilities#MAX_SKILLS} 同源。 */
  public static final int MAX_SKILLS = DaemonCapabilities.MAX_SKILLS;

  private static final DaemonSkillSourceSnapshotCodec SNAPSHOT_CODEC =
      new DaemonSkillSourceSnapshotCodec();

  public String encode(DaemonCapabilities capabilities) {
    Objects.requireNonNull(capabilities, "capabilities");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("version", capabilities.version());
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", capabilities.environment().operatingSystem().wireValue());
    environment.put("timeZone", capabilities.environment().timeZone());
    environment.put("note", capabilities.environment().note());
    environment.put("rootPath", capabilities.environment().rootPath());
    root.put("sourceSetVersion", capabilities.sourceSetVersion());
    ArrayNode sources = root.putArray("skillSources");
    for (DaemonSkillSourceSnapshot source : capabilities.skillSources()) {
      sources.add(SNAPSHOT_CODEC.encodeNode(source));
    }
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode READY capabilities payload", error);
    }
  }

  public DaemonCapabilities decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("malformed READY payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("READY payload must be an object");
    }
    root.fieldNames()
        .forEachRemaining(
            field -> {
              if (!ROOT_FIELDS.contains(field)) {
                throw new DaemonProtocolException("unexpected READY field: " + field);
              }
            });
    int version = requiredVersion(root);
    DaemonEnvironmentInfo environment = decodeEnvironment(requiredObject(root, "environment"));
    long sourceSetVersion = requiredSourceSetVersion(root);
    List<DaemonSkillSourceSnapshot> skillSources = decodeSources(root);
    try {
      return new DaemonCapabilities(version, environment, sourceSetVersion, skillSources);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "READY capabilities validation failed: " + error.getMessage(), error);
    }
  }

  private static int requiredVersion(ObjectNode root) {
    JsonNode version = root.get("version");
    if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()) {
      throw new DaemonProtocolException("READY payload.version must be an integer");
    }
    if (version.intValue() != DaemonCapabilities.VERSION) {
      throw new DaemonProtocolException(
          "unsupported READY capabilities version: " + version.intValue());
    }
    return version.intValue();
  }

  /** {@code sourceSetVersion} 是必填非负整数：缺失、负数与非整数值都按协议错误拒绝，不做缺省或字符串强转。 */
  private static long requiredSourceSetVersion(ObjectNode root) {
    JsonNode sourceSetVersion = root.get("sourceSetVersion");
    if (sourceSetVersion == null
        || !sourceSetVersion.isIntegralNumber()
        || !sourceSetVersion.canConvertToLong()) {
      throw new DaemonProtocolException("READY payload.sourceSetVersion must be an integer");
    }
    if (sourceSetVersion.longValue() < 0) {
      throw new DaemonProtocolException("READY payload.sourceSetVersion must not be negative");
    }
    return sourceSetVersion.longValue();
  }

  private static ObjectNode requiredObject(ObjectNode root, String field) {
    JsonNode node = root.get(field);
    if (!(node instanceof ObjectNode objectNode)) {
      throw new DaemonProtocolException("READY payload." + field + " must be an object");
    }
    return objectNode;
  }

  private static DaemonEnvironmentInfo decodeEnvironment(ObjectNode node) {
    node.fieldNames()
        .forEachRemaining(
            field -> {
              if (!ENVIRONMENT_FIELDS.contains(field)) {
                throw new DaemonProtocolException("unexpected READY environment field: " + field);
              }
            });
    String operatingSystemText = text(node, "operatingSystem", "READY environment");
    String timeZone = text(node, "timeZone", "READY environment");
    String note = text(node, "note", "READY environment");
    String rootPath = text(node, "rootPath", "READY environment");
    try {
      return new DaemonEnvironmentInfo(
          DaemonOperatingSystem.fromWireValue(operatingSystemText), timeZone, note, rootPath);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "READY environment validation failed: " + error.getMessage(), error);
    }
  }

  private static List<DaemonSkillSourceSnapshot> decodeSources(ObjectNode root) {
    JsonNode sourcesNode = root.get("skillSources");
    if (sourcesNode == null || !sourcesNode.isArray()) {
      throw new DaemonProtocolException("READY payload.skillSources must be an array");
    }
    if (sourcesNode.size() > MAX_SOURCES) {
      throw new DaemonProtocolException(
          "READY skillSources must not exceed " + MAX_SOURCES + " entries");
    }
    Map<UUID, DaemonSkillSourceSnapshot> seen = new LinkedHashMap<>();
    List<DaemonSkillSourceSnapshot> result = new ArrayList<>();
    int skillCount = 0;
    int sourceIndex = 0;
    for (JsonNode element : sourcesNode) {
      DaemonSkillSourceSnapshot snapshot =
          SNAPSHOT_CODEC.decodeNode(element, "READY skillSources[" + sourceIndex + "]");
      skillCount += snapshot.skills().size();
      if (skillCount > MAX_SKILLS) {
        throw new DaemonProtocolException(
            "READY skills must not exceed " + MAX_SKILLS + " entries in total");
      }
      if (seen.putIfAbsent(snapshot.sourceId(), snapshot) != null) {
        throw new DaemonProtocolException("duplicate READY skill source: " + snapshot.sourceId());
      }
      result.add(snapshot);
      sourceIndex++;
    }
    return List.copyOf(result);
  }

  private static String text(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(context + "." + field + " must be non-blank text");
    }
    return value.textValue();
  }
}

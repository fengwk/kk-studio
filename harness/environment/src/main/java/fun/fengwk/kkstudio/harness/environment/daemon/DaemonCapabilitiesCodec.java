package fun.fengwk.kkstudio.harness.environment.daemon;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.Set;

/**
 * Daemon READY 宿主 metadata payload 的严格 codec：版本化、类型化，拒绝未知字段、重复键、尾随内容与缺失字段。
 *
 * <p>wire shape：{@code
 * {"version":6,"daemonVersion":"1.0.10","environment":{"operatingSystem":...,"timeZone":...,"userName":...,"homeDirectory":...,"note":...,"tempDirectory":...,"architecture":...}}}。两侧都只接受这一形状，任何多余字段都是协议错误。
 *
 * <p>{@code architecture} 是唯一允许 JSON null 的字段：null 表示宿主架构事实未知，非 null 时必须是有界的合法标识字符串。
 */
public final class DaemonCapabilitiesCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> ROOT_FIELDS = Set.of("version", "daemonVersion", "environment");
  private static final Set<String> ENVIRONMENT_FIELDS =
      Set.of(
          "operatingSystem",
          "timeZone",
          "userName",
          "homeDirectory",
          "note",
          "tempDirectory",
          "architecture");

  public String encode(DaemonCapabilities capabilities) {
    try {
      return MAPPER.writeValueAsString(encodeObject(capabilities));
    } catch (JacksonException error) {
      throw new DaemonProtocolException("cannot encode READY capabilities payload", error);
    }
  }

  ObjectNode encodeObject(DaemonCapabilities capabilities) {
    Objects.requireNonNull(capabilities, "capabilities");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("version", capabilities.version());
    root.put("daemonVersion", capabilities.daemonVersion());
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", capabilities.environment().operatingSystem().wireValue());
    environment.put("timeZone", capabilities.environment().timeZone());
    environment.put("userName", capabilities.environment().userName());
    environment.put("homeDirectory", capabilities.environment().homeDirectory());
    environment.put("note", capabilities.environment().note());
    environment.put("tempDirectory", capabilities.environment().tempDirectory());
    if (capabilities.environment().architecture() == null) {
      environment.putNull("architecture");
    } else {
      environment.put("architecture", capabilities.environment().architecture());
    }
    return root;
  }

  public DaemonCapabilities decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("malformed READY payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("READY payload must be an object");
    }
    return decodeObject(root);
  }

  DaemonCapabilities decodeObject(ObjectNode root) {
    root.propertyNames()
        .forEach(
            field -> {
              if (!ROOT_FIELDS.contains(field)) {
                throw new DaemonProtocolException("unexpected READY field: " + field);
              }
            });
    int version = requiredVersion(root);
    String daemonVersion = text(root, "daemonVersion", "READY payload");
    DaemonEnvironmentInfo environment = decodeEnvironment(requiredObject(root, "environment"));
    try {
      return new DaemonCapabilities(version, daemonVersion, environment);
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

  private static ObjectNode requiredObject(ObjectNode root, String field) {
    JsonNode node = root.get(field);
    if (!(node instanceof ObjectNode objectNode)) {
      throw new DaemonProtocolException("READY payload." + field + " must be an object");
    }
    return objectNode;
  }

  private static DaemonEnvironmentInfo decodeEnvironment(ObjectNode node) {
    node.propertyNames()
        .forEach(
            field -> {
              if (!ENVIRONMENT_FIELDS.contains(field)) {
                throw new DaemonProtocolException("unexpected READY environment field: " + field);
              }
            });
    String operatingSystemText = text(node, "operatingSystem", "READY environment");
    String timeZone = text(node, "timeZone", "READY environment");
    String userName = text(node, "userName", "READY environment");
    String homeDirectory = text(node, "homeDirectory", "READY environment");
    String note = text(node, "note", "READY environment");
    String tempDirectory = text(node, "tempDirectory", "READY environment");
    String architecture = nullableText(node, "architecture", "READY environment");
    try {
      return new DaemonEnvironmentInfo(
          DaemonOperatingSystem.fromWireValue(operatingSystemText),
          timeZone,
          userName,
          homeDirectory,
          note,
          tempDirectory,
          architecture);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "READY environment validation failed: " + error.getMessage(), error);
    }
  }

  private static String text(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString() || value.stringValue().isBlank()) {
      throw new DaemonProtocolException(context + "." + field + " must be non-blank text");
    }
    return value.stringValue();
  }

  /**
   * 读取允许明确未知（JSON null）的字段：键必须存在，null 与有界非空文本之外的一切取值都是协议错误。
   *
   * <p>错误信息只描述字段与形状，不回显被拒绝的值。
   */
  private static String nullableText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null) {
      throw new DaemonProtocolException(context + "." + field + " is required");
    }
    if (value.isNull()) {
      return null;
    }
    if (!value.isString() || value.stringValue().isBlank()) {
      throw new DaemonProtocolException(context + "." + field + " must be non-blank text or null");
    }
    return value.stringValue();
  }
}

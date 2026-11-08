package fun.fengwk.kkstudio.platform.daemon;

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
 * Daemon READY 宿主 metadata payload 的严格 codec：类型化，拒绝未知字段、重复键、尾随内容与缺失字段。
 *
 * <p>wire shape：{@code
 * {"daemonVersion":"1.0.10","hostInfo":{"operatingSystem":...,"timeZone":...,"userName":...,"homeDirectory":...,"tempDirectory":...,"architecture":...}}}。两侧都只接受这一形状，任何多余字段都是协议错误。
 *
 * <p>{@code architecture} 是唯一允许 JSON null 的字段：null 表示宿主架构事实未知，非 null 时必须是有界的合法标识字符串。
 */
public final class DaemonReadyMetadataCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> ROOT_FIELDS = Set.of("daemonVersion", "hostInfo");
  private static final Set<String> HOST_INFO_FIELDS =
      Set.of(
          "operatingSystem",
          "timeZone",
          "userName",
          "homeDirectory",
          "tempDirectory",
          "architecture");

  public String encode(DaemonReadyMetadata readyMetadata) {
    try {
      return MAPPER.writeValueAsString(encodeObject(readyMetadata));
    } catch (JacksonException error) {
      throw new DaemonProtocolException("cannot encode READY metadata payload", error);
    }
  }

  ObjectNode encodeObject(DaemonReadyMetadata readyMetadata) {
    Objects.requireNonNull(readyMetadata, "readyMetadata");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("daemonVersion", readyMetadata.daemonVersion());
    ObjectNode hostInfo = root.putObject("hostInfo");
    hostInfo.put("operatingSystem", readyMetadata.hostInfo().operatingSystem().wireValue());
    hostInfo.put("timeZone", readyMetadata.hostInfo().timeZone());
    hostInfo.put("userName", readyMetadata.hostInfo().userName());
    hostInfo.put("homeDirectory", readyMetadata.hostInfo().homeDirectory());
    hostInfo.put("tempDirectory", readyMetadata.hostInfo().tempDirectory());
    if (readyMetadata.hostInfo().architecture() == null) {
      hostInfo.putNull("architecture");
    } else {
      hostInfo.put("architecture", readyMetadata.hostInfo().architecture());
    }
    return root;
  }

  public DaemonReadyMetadata decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("malformed READY metadata payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("READY metadata payload must be an object");
    }
    return decodeObject(root);
  }

  DaemonReadyMetadata decodeObject(ObjectNode root) {
    root.propertyNames()
        .forEach(
            field -> {
              if (!ROOT_FIELDS.contains(field)) {
                throw new DaemonProtocolException("unexpected READY metadata field: " + field);
              }
            });
    String daemonVersion = text(root, "daemonVersion", "READY metadata payload");
    DaemonHostInfo hostInfo = decodeHostInfo(requiredObject(root, "hostInfo"));
    try {
      return new DaemonReadyMetadata(daemonVersion, hostInfo);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "READY metadata validation failed: " + error.getMessage(), error);
    }
  }

  private static ObjectNode requiredObject(ObjectNode root, String field) {
    JsonNode node = root.get(field);
    if (!(node instanceof ObjectNode objectNode)) {
      throw new DaemonProtocolException("READY metadata." + field + " must be an object");
    }
    return objectNode;
  }

  private static DaemonHostInfo decodeHostInfo(ObjectNode node) {
    node.propertyNames()
        .forEach(
            field -> {
              if (!HOST_INFO_FIELDS.contains(field)) {
                throw new DaemonProtocolException(
                    "unexpected READY metadata hostInfo field: " + field);
              }
            });
    String operatingSystemText = text(node, "operatingSystem", "READY metadata hostInfo");
    String timeZone = text(node, "timeZone", "READY metadata hostInfo");
    String userName = text(node, "userName", "READY metadata hostInfo");
    String homeDirectory = text(node, "homeDirectory", "READY metadata hostInfo");
    String tempDirectory = text(node, "tempDirectory", "READY metadata hostInfo");
    String architecture = nullableText(node, "architecture", "READY metadata hostInfo");
    try {
      return new DaemonHostInfo(
          DaemonOperatingSystem.fromWireValue(operatingSystemText),
          timeZone,
          userName,
          homeDirectory,
          tempDirectory,
          architecture);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "READY metadata hostInfo validation failed: " + error.getMessage(), error);
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

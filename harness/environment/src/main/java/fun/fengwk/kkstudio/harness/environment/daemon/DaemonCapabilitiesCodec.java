package fun.fengwk.kkstudio.harness.environment.daemon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.Set;

/**
 * Daemon READY 宿主 metadata payload 的严格 codec：版本化、类型化，拒绝未知字段、重复键、尾随内容与缺失字段。
 *
 * <p>wire shape：{@code
 * {"version":1,"environment":{"operatingSystem":...,"timeZone":...,"note":...,"rootPath":...}}}。两侧都只接受这一形状，任何多余字段都是协议错误。
 */
public final class DaemonCapabilitiesCodec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final Set<String> ROOT_FIELDS = Set.of("version", "environment");
  private static final Set<String> ENVIRONMENT_FIELDS =
      Set.of("operatingSystem", "timeZone", "note", "rootPath");

  public String encode(DaemonCapabilities capabilities) {
    Objects.requireNonNull(capabilities, "capabilities");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("version", capabilities.version());
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", capabilities.environment().operatingSystem().wireValue());
    environment.put("timeZone", capabilities.environment().timeZone());
    environment.put("note", capabilities.environment().note());
    environment.put("rootPath", capabilities.environment().rootPath());
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
    try {
      return new DaemonCapabilities(version, environment);
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

  private static String text(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(context + "." + field + " must be non-blank text");
    }
    return value.textValue();
  }
}

package fun.fengwk.kkstudio.harness.environment.daemon;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolException;

import java.util.Objects;

/** Daemon READY 宿主 metadata payload 的严格 codec。 */
public final class DaemonCapabilitiesCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public String encode(DaemonCapabilities capabilities) {
    try {
      return MAPPER.writeValueAsString(encodeObject(capabilities));
    } catch (JacksonException error) {
      throw new ControlProtocolException("cannot encode READY capabilities payload", error);
    }
  }

  public ObjectNode encodeObject(DaemonCapabilities capabilities) {
    Objects.requireNonNull(capabilities, "capabilities");
    ObjectNode root = MAPPER.createObjectNode();
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
      throw new ControlProtocolException("malformed capabilities payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new ControlProtocolException("capabilities payload must be an object");
    }
    return decodeObject(root);
  }

  public DaemonCapabilities decodeObject(ObjectNode root) {
    Objects.requireNonNull(root, "root");
    String daemonVersion = requiredText(root, "daemonVersion");
    JsonNode envNode = root.get("environment");
    if (envNode == null || !envNode.isObject()) {
      throw new ControlProtocolException("capabilities.environment must be an object");
    }
    ObjectNode environment = (ObjectNode) envNode;
    DaemonOperatingSystem operatingSystem =
        DaemonOperatingSystem.fromWireValue(requiredText(environment, "operatingSystem"));
    String timeZone = requiredText(environment, "timeZone");
    String userName = requiredText(environment, "userName");
    String homeDirectory = requiredText(environment, "homeDirectory");
    String note = requiredText(environment, "note");
    String tempDirectory = requiredText(environment, "tempDirectory");
    String architecture = null;
    JsonNode archNode = environment.get("architecture");
    if (archNode != null && !archNode.isNull()) {
      architecture = requiredText(environment, "architecture");
    }
    return new DaemonCapabilities(
        daemonVersion,
        new DaemonEnvironmentInfo(
            operatingSystem, timeZone, userName, homeDirectory, note, tempDirectory, architecture));
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode v = node.get(field);
    if (v == null || !v.isString() || v.stringValue().isBlank()) {
      throw new ControlProtocolException(field + " must be a non-blank string");
    }
    return v.stringValue();
  }
}

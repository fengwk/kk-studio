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
 * READY v6 payload 的严格 codec：宿主 metadata 与必需的 runtimeStatus。
 *
 * <p>wire shape：{@code
 * {"version":6,"daemonVersion":...,"environment":{...},"runtimeStatus":{"revision":"1","status":{"daemonVersion":...,"update":null}}}}。
 * 宿主 metadata 可单独经 {@link DaemonCapabilitiesCodec} 持久化到 {@code runtime_info}；当前运行状态只保留在连接内存中。
 */
public final class DaemonReadyCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> READY_FIELDS =
      Set.of("version", "daemonVersion", "environment", "runtimeStatus");

  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
  private final DaemonRuntimeStatusCodec runtimeStatusCodec = new DaemonRuntimeStatusCodec();

  public String encode(DaemonReady ready) {
    Objects.requireNonNull(ready, "ready");
    ObjectNode root = capabilitiesCodec.encodeObject(ready.capabilities());
    root.set("runtimeStatus", runtimeStatusCodec.encodeNode(ready.runtimeStatus()));
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("cannot encode READY payload", error);
    }
  }

  public DaemonReady decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("malformed READY payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("READY payload must be an object");
    }
    for (String field : root.propertyNames()) {
      if (!READY_FIELDS.contains(field)) {
        throw new DaemonProtocolException("unexpected READY field: " + field);
      }
    }
    JsonNode runtimeStatus = root.get("runtimeStatus");
    if (runtimeStatus == null) {
      throw new DaemonProtocolException("READY runtimeStatus is required");
    }
    ObjectNode host = root.deepCopy();
    host.remove("runtimeStatus");
    DaemonCapabilities capabilities = capabilitiesCodec.decodeObject(host);
    DaemonRuntimeSnapshot snapshot = runtimeStatusCodec.decodeNode(runtimeStatus);
    try {
      return new DaemonReady(capabilities, snapshot);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "READY runtimeStatus validation failed: " + error.getMessage(), error);
    }
  }
}

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
 * HEARTBEAT payload 的严格 codec：{@code {runtimeStatus,ideLeaseRound}}。
 *
 * <p>两个字段都必须存在；{@code ideLeaseRound} 明确为 null 或规范正十进制字符串。未知字段、重复键与尾随内容一律拒绝。
 */
public final class DaemonHeartbeatCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> FIELDS = Set.of("runtimeStatus", "ideLeaseRound");

  private final DaemonRuntimeStatusCodec runtimeStatusCodec = new DaemonRuntimeStatusCodec();

  public String encode(DaemonHeartbeat heartbeat) {
    Objects.requireNonNull(heartbeat, "heartbeat");
    ObjectNode root = MAPPER.createObjectNode();
    root.set("runtimeStatus", runtimeStatusCodec.encodeNode(heartbeat.runtimeStatus()));
    if (heartbeat.ideLeaseRound() == null) {
      root.putNull("ideLeaseRound");
    } else {
      root.put("ideLeaseRound", Long.toString(heartbeat.ideLeaseRound()));
    }
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("cannot encode HEARTBEAT payload", error);
    }
  }

  public DaemonHeartbeat decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("malformed HEARTBEAT payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("HEARTBEAT payload must be an object");
    }
    for (String field : root.propertyNames()) {
      if (!FIELDS.contains(field)) {
        throw new DaemonProtocolException("unexpected HEARTBEAT field: " + field);
      }
    }
    JsonNode runtimeStatus = root.get("runtimeStatus");
    if (runtimeStatus == null) {
      throw new DaemonProtocolException("HEARTBEAT runtimeStatus is required");
    }
    if (!root.has("ideLeaseRound")) {
      throw new DaemonProtocolException("HEARTBEAT ideLeaseRound is required");
    }
    Long ideLeaseRound = null;
    JsonNode round = root.get("ideLeaseRound");
    if (round != null && !round.isNull()) {
      if (!round.isString()) {
        throw new DaemonProtocolException(
            "HEARTBEAT ideLeaseRound must be a canonical positive decimal string or null");
      }
      try {
        ideLeaseRound = DaemonWireNumbers.positiveLong(round.stringValue(), "ideLeaseRound");
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(error.getMessage(), error);
      }
    }
    try {
      return new DaemonHeartbeat(runtimeStatusCodec.decodeNode(runtimeStatus), ideLeaseRound);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "HEARTBEAT validation failed: " + error.getMessage(), error);
    }
  }
}

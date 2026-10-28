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
 * runtimeStatus 的严格 codec：{@code {revision,status}}，status 为 {@code {daemonVersion,update}}。
 *
 * <p>revision 是正十进制字符串；update 必须明确存在，取 null 或下载进度对象。拒绝未知字段、重复键、尾随内容、越界计数与 总量小于已下载量。
 */
public final class DaemonRuntimeStatusCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> ROOT_FIELDS = Set.of("revision", "status");
  private static final Set<String> STATUS_FIELDS = Set.of("daemonVersion", "update");
  private static final Set<String> UPDATE_FIELDS =
      Set.of("targetVersion", "downloadedBytes", "totalBytes");

  public String encode(DaemonRuntimeSnapshot snapshot) {
    return write(encodeNode(snapshot));
  }

  ObjectNode encodeNode(DaemonRuntimeSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("revision", Long.toString(snapshot.revision()));
    ObjectNode status = root.putObject("status");
    status.put("daemonVersion", snapshot.status().daemonVersion());
    DaemonUpdateProgress update = snapshot.status().update();
    if (update == null) {
      status.putNull("update");
    } else {
      ObjectNode updateNode = status.putObject("update");
      updateNode.put("targetVersion", update.targetVersion());
      updateNode.put("downloadedBytes", update.downloadedBytes());
      if (update.totalBytes() == null) {
        updateNode.putNull("totalBytes");
      } else {
        updateNode.put("totalBytes", update.totalBytes());
      }
    }
    return root;
  }

  public DaemonRuntimeSnapshot decode(String json) {
    JsonNode value = read(json);
    return decodeNode(value);
  }

  DaemonRuntimeSnapshot decodeNode(JsonNode value) {
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("runtimeStatus must be an object");
    }
    rejectUnknown(root, ROOT_FIELDS, "runtimeStatus");
    String revisionText = requiredString(root, "revision", "runtimeStatus");
    long revision;
    try {
      revision = DaemonWireNumbers.positiveLong(revisionText, "runtimeStatus.revision");
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(error.getMessage(), error);
    }
    JsonNode statusNode = root.get("status");
    if (!(statusNode instanceof ObjectNode status)) {
      throw new DaemonProtocolException("runtimeStatus.status must be an object");
    }
    rejectUnknown(status, STATUS_FIELDS, "runtimeStatus.status");
    String daemonVersion = requiredString(status, "daemonVersion", "runtimeStatus.status");
    if (!status.has("update")) {
      throw new DaemonProtocolException("runtimeStatus.status.update is required");
    }
    DaemonUpdateProgress update = decodeUpdate(status.get("update"));
    try {
      return new DaemonRuntimeSnapshot(revision, new DaemonRuntimeStatus(daemonVersion, update));
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "runtimeStatus validation failed: " + error.getMessage(), error);
    }
  }

  private static DaemonUpdateProgress decodeUpdate(JsonNode value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (!(value instanceof ObjectNode update)) {
      throw new DaemonProtocolException("runtimeStatus.status.update must be an object or null");
    }
    rejectUnknown(update, UPDATE_FIELDS, "runtimeStatus.status.update");
    String targetVersion = requiredString(update, "targetVersion", "runtimeStatus.status.update");
    long downloadedBytes;
    Long totalBytes;
    try {
      downloadedBytes =
          DaemonWireNumbers.safeNonNegative(
              update.get("downloadedBytes"), "runtimeStatus.status.update.downloadedBytes");
      JsonNode totalNode = update.get("totalBytes");
      if (totalNode == null) {
        throw new DaemonProtocolException("runtimeStatus.status.update.totalBytes is required");
      }
      totalBytes =
          totalNode.isNull()
              ? null
              : DaemonWireNumbers.safeNonNegative(
                  totalNode, "runtimeStatus.status.update.totalBytes");
      return new DaemonUpdateProgress(targetVersion, downloadedBytes, totalBytes);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "runtimeStatus update validation failed: " + error.getMessage(), error);
    }
  }

  private static JsonNode read(String json) {
    try {
      JsonNode value = MAPPER.readTree(json);
      if (value == null || value.isMissingNode()) {
        throw new DaemonProtocolException("runtimeStatus must not be null");
      }
      return value;
    } catch (JacksonException error) {
      throw new DaemonProtocolException("malformed runtimeStatus", error);
    }
  }

  private static void rejectUnknown(ObjectNode node, Set<String> allowed, String context) {
    for (String field : node.propertyNames()) {
      if (!allowed.contains(field)) {
        throw new DaemonProtocolException(context + " has unknown field: " + field);
      }
    }
  }

  private static String requiredString(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new DaemonProtocolException(context + "." + field + " must be a string");
    }
    return value.stringValue();
  }

  private static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("cannot encode runtimeStatus", error);
    }
  }
}

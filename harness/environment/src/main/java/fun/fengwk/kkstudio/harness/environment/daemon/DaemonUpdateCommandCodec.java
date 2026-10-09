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
 * 受管更新命令 payload 的严格 codec：固定四个字段，拒绝未知字段、重复键与尾随内容。
 *
 * <p>wire shape：{@code
 * {"operationId":"<uuid>","targetVersion":"1.0.9","artifactUrl":"https://...","artifactSha256":"<64
 * hex>"}}。
 */
public final class DaemonUpdateCommandCodec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final Set<String> FIELDS =
      Set.of("operationId", "targetVersion", "artifactUrl", "artifactSha256");

  public String encode(DaemonUpdateCommand command) {
    Objects.requireNonNull(command, "command");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("operationId", command.operationId());
    root.put("targetVersion", command.targetVersion());
    root.put("artifactUrl", command.artifactUrl());
    root.put("artifactSha256", command.artifactSha256());
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode update command payload", error);
    }
  }

  public DaemonUpdateCommand decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("malformed update command payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("update command payload must be an object");
    }
    root.fieldNames()
        .forEachRemaining(
            field -> {
              if (!FIELDS.contains(field)) {
                throw new DaemonProtocolException("unexpected update command field: " + field);
              }
            });
    String operationId = text(root, "operationId");
    String targetVersion = text(root, "targetVersion");
    String artifactUrl = text(root, "artifactUrl");
    String artifactSha256 = text(root, "artifactSha256");
    try {
      return new DaemonUpdateCommand(operationId, targetVersion, artifactUrl, artifactSha256);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "update command validation failed: " + error.getMessage(), error);
    }
  }

  private static String text(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException("update command." + field + " must be non-blank text");
    }
    return value.textValue();
  }
}

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
 * 受管更新阶段回执 payload 的严格 codec。
 *
 * <p>wire shape：{@code
 * {"operationId":"<uuid>","phase":"ACCEPTED|PREPARED|FAILED","message":<nullable text>}}。{@code
 * message} 缺省或 {@code null} 均等价于无说明；任何其它未知字段都是协议错误。
 */
public final class DaemonUpdateResultCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> FIELDS = Set.of("operationId", "phase", "message");

  public String encode(DaemonUpdateResult result) {
    Objects.requireNonNull(result, "result");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("operationId", result.operationId());
    root.put("phase", result.phase().name());
    if (result.message() != null) {
      root.put("message", result.message());
    }
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("cannot encode update result payload", error);
    }
  }

  public DaemonUpdateResult decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("malformed update result payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("update result payload must be an object");
    }
    root.propertyNames()
        .forEach(
            field -> {
              if (!FIELDS.contains(field)) {
                throw new DaemonProtocolException("unexpected update result field: " + field);
              }
            });
    String operationId = requiredText(root, "operationId");
    String phaseText = requiredText(root, "phase");
    DaemonUpdatePhase phase;
    try {
      phase = DaemonUpdatePhase.valueOf(phaseText);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("unknown update phase: " + phaseText, error);
    }
    String message = optionalText(root, "message");
    try {
      return new DaemonUpdateResult(operationId, phase, message);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "update result validation failed: " + error.getMessage(), error);
    }
  }

  private static String requiredText(ObjectNode root, String field) {
    String value = optionalText(root, field);
    if (value == null || value.isBlank()) {
      throw new DaemonProtocolException("update result." + field + " must be non-blank text");
    }
    return value;
  }

  private static String optionalText(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new DaemonProtocolException("update result." + field + " must be text");
    }
    return value.stringValue();
  }
}

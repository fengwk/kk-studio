package fun.fengwk.kkstudio.platform.harness.builtin.realtime;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@code harness_stream_catchup} 专用通知的严格 JSON codec。
 *
 * <p>Envelope 只包含产生请求的节点与精确追帧目标：{@code originNodeId}、{@code threadId}、{@code invocationId}、{@code
 * attempt}。任何额外/缺失字段、重复键、尾随 token、非 canonical UUID 或非正整数 attempt 都被拒绝；编码结果也是固定的 canonical
 * JSON，供接收方做逐字节回环判定。
 */
public final class HarnessStreamCatchupNotificationCodec {

  private static final ObjectMapper MAPPER = HarnessJson.STRICT;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Set<String> FIELDS =
      Set.of("originNodeId", "threadId", "invocationId", "attempt");

  /** 编码 canonical envelope；字段顺序固定为 originNodeId、threadId、invocationId、attempt。 */
  public String encode(Envelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    ObjectNode node = NODES.objectNode();
    node.put("originNodeId", envelope.originNodeId().toString());
    node.put("threadId", envelope.threadId().toString());
    node.put("invocationId", envelope.invocationId().toString());
    node.put("attempt", envelope.attempt());
    return write(node);
  }

  /**
   * 解码并验证 canonical envelope；任何 malformed、unknown 或非 canonical payload 都抛 {@link
   * IllegalArgumentException}。
   */
  public Envelope decode(String payload) {
    Objects.requireNonNull(payload, "payload");
    JsonNode root;
    try {
      root = MAPPER.readTree(payload);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("malformed stream catchup notification JSON", error);
    }
    ObjectNode node = JsonCodecSupport.object(root, "stream catchup notification");
    JsonCodecSupport.requireFields(node, FIELDS, "stream catchup notification");
    Envelope envelope =
        new Envelope(
            requiredCanonicalUuid(node, "originNodeId"),
            requiredCanonicalUuid(node, "threadId"),
            requiredCanonicalUuid(node, "invocationId"),
            requiredPositiveInt(node, "attempt"));
    if (!encode(envelope).equals(payload)) {
      throw new IllegalArgumentException("stream catchup notification must use canonical JSON");
    }
    return envelope;
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException(
          "stream catchup notification." + field + " must be a string");
    }
    return value.stringValue();
  }

  private static UUID requiredCanonicalUuid(ObjectNode node, String field) {
    String text = requiredText(node, field);
    try {
      return Uuids.parseCanonical(text, "stream catchup notification." + field);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "stream catchup notification." + field + " must use canonical UUID text", error);
    }
  }

  private static int requiredPositiveInt(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isInt()) {
      throw new IllegalArgumentException(
          "stream catchup notification." + field + " must be a JSON integer");
    }
    int attempt = value.intValue();
    if (attempt <= 0) {
      throw new IllegalArgumentException(
          "stream catchup notification." + field + " must be positive");
    }
    return attempt;
  }

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("cannot encode stream catchup notification JSON", error);
    }
  }

  /** 只包含跨节点追帧必需事实的不可变 envelope。 */
  public record Envelope(UUID originNodeId, UUID threadId, UUID invocationId, int attempt) {

    public Envelope {
      Objects.requireNonNull(originNodeId, "originNodeId");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(invocationId, "invocationId");
      if (attempt <= 0) {
        throw new IllegalArgumentException("attempt must be positive");
      }
    }
  }
}

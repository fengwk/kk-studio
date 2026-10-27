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
 * DATA_BIND payload 的严格 codec：{@code {generation,ticket,streamUrl}}。
 *
 * <p>generation 为规范正十进制字符串，ticket 为 43 字符 base64url，streamUrl 为受控 Studio 同源绝对地址。本 codec
 * 只实施结构校验；origin 策略由签发数据的业务层执行。
 */
public final class DaemonDataBindCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> FIELDS = Set.of("generation", "ticket", "streamUrl");

  public String encode(DaemonDataBind dataBind) {
    Objects.requireNonNull(dataBind, "dataBind");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("generation", Long.toString(dataBind.generation()));
    root.put("ticket", dataBind.ticket());
    root.put("streamUrl", dataBind.streamUrl().toString());
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("cannot encode DATA_BIND payload", error);
    }
  }

  public DaemonDataBind decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("malformed DATA_BIND payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("DATA_BIND payload must be an object");
    }
    for (String field : root.propertyNames()) {
      if (!FIELDS.contains(field)) {
        throw new DaemonProtocolException("unexpected DATA_BIND field: " + field);
      }
    }
    String generationText = requiredString(root, "generation");
    String ticket = requiredString(root, "ticket");
    String streamUrlText = requiredString(root, "streamUrl");
    try {
      return new DaemonDataBind(
          DaemonWireNumbers.positiveLong(generationText, "DATA_BIND.generation"),
          ticket,
          DaemonDataBind.parseStreamUrl(streamUrlText));
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "DATA_BIND validation failed: " + error.getMessage(), error);
    }
  }

  private static String requiredString(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new DaemonProtocolException("DATA_BIND." + field + " must be a string");
    }
    return value.stringValue();
  }
}

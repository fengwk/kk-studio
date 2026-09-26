package fun.fengwk.kkstudio.harness.runtime.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 单个 model variant 携带的厂商原生协议请求选项。
 *
 * <p>值对象只持有 canonical JSON object 文本：输入必须是严格 JSON object（重复键、trailing token、非 object 一律拒绝），
 * 并被规范化为无多余空白的紧凑文本；{@code null} 与空白等价于空 object（{@link #EMPTY}），因此「未声明选项」与「声明了空 object」是同一语义。输入的
 * UTF-8 字节数受 {@link #MAX_UTF8_BYTES} 限制，避免 variant 变成无限 payload 的载体。
 *
 * <p>规范化使用不做本地约定的独立 mapper：本地 JSON 约定（{@code Long} 字符串化、null 省略等）只描述本项目自己的 wire
 * 形态，绝不能改写厂商原生事实；整数与小数保持 JSON 数值类型且不经过二进制浮点数，但无语义意义的小数尾零可能被移除。
 *
 * <p>异常消息只描述违反的约束，绝不回显 payload。本类不决定任何协议字段的 merge policy：官方字段如何并入请求体、哪些运行时 所有权字段必须优先，由各协议编码器决定。
 */
public record ProviderProtocolOptions(String canonicalJson) {

  /** canonical JSON 允许的最大 UTF-8 字节数：64 KiB；原生选项只是 variant 级附加片段，必须远小于请求体上限。 */
  public static final int MAX_UTF8_BYTES = 64 * 1024;

  private static final String EMPTY_JSON = "{}";
  private static final ObjectMapper STRICT_MAPPER = newStrictMapper();

  /** 空 object：未声明任何原生协议选项。 */
  public static final ProviderProtocolOptions EMPTY = new ProviderProtocolOptions(EMPTY_JSON);

  public ProviderProtocolOptions {
    canonicalJson = canonicalize(canonicalJson);
  }

  /** 从已解析的 JSON object 构造；{@code null} 等价于空 object，非 JSON 值抛 {@link IllegalArgumentException}。 */
  public static ProviderProtocolOptions of(Map<String, Object> options) {
    if (options == null) {
      return EMPTY;
    }
    try {
      return new ProviderProtocolOptions(STRICT_MAPPER.writeValueAsString(options));
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("protocolOptions must contain JSON values only");
    }
  }

  /** 是否未声明任何原生协议选项。 */
  public boolean isEmpty() {
    return EMPTY_JSON.equals(canonicalJson);
  }

  private static String canonicalize(String json) {
    if (json == null || json.isBlank()) {
      return EMPTY_JSON;
    }
    requireWithinByteLimit(json);
    JsonNode node;
    try {
      node = STRICT_MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("protocolOptions must be a strict JSON object");
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("protocolOptions must be a strict JSON object");
    }
    try {
      return STRICT_MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("protocolOptions must be a strict JSON object");
    }
  }

  /** UTF-8 字节数不小于 UTF-16 字符数，因此先用字符数做零成本预筛，再按真实字节数判定。 */
  private static void requireWithinByteLimit(String json) {
    if (json.length() > MAX_UTF8_BYTES
        || json.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "protocolOptions must not exceed " + MAX_UTF8_BYTES + " UTF-8 bytes");
    }
  }

  private static ObjectMapper newStrictMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    return mapper;
  }
}

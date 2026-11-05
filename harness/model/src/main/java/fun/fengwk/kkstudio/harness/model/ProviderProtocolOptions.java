package fun.fengwk.kkstudio.harness.model;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * 单个 model variant 携带的厂商原生协议请求选项。
 *
 * <p>值对象只持有 canonical JSON object 文本：输入必须是严格 JSON object（重复键、trailing token、非 object 一律拒绝），
 * 并被规范化为无多余空白的紧凑文本；{@code null} 与空白等价于空 object（{@link #EMPTY}），因此「未声明选项」与「声明了空 object」是同一语义。输入的
 * UTF-8 字节数受 {@link #MAX_UTF8_BYTES} 限制，避免 variant 变成无限 payload 的载体。
 *
 * <p>本类的 canonical 构造器是 {@code protocolOptions} 唯一的解析与规范化入口：API/durable config 的 JSON string 与
 * descriptor 中的嵌套 object 都先转成文本再进入这里；其他位置只提取输入并把失败映射成自己的领域错误，不复制严格语法、字节上限或 canonical 规则。拒绝时抛出
 * {@link InvalidOptionsException}（仍是 {@link IllegalArgumentException}），调用方按 {@link InvalidReason}
 * 区分失败语义，不解析消息文本。
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
  private static final ObjectMapper STRICT_MAPPER = HarnessJson.STRICT_OPTIONS;

  /** 空 object：未声明任何原生协议选项。 */
  public static final ProviderProtocolOptions EMPTY = new ProviderProtocolOptions(EMPTY_JSON);

  /** 拒绝原因：调用方按语义映射错误，不依赖消息文本。 */
  public enum InvalidReason {
    /** 非严格 JSON object：语法错误、重复键、尾随 token 或根节点不是 object。 */
    MALFORMED,
    /** 输入的 UTF-8 字节数超过 {@link ProviderProtocolOptions#MAX_UTF8_BYTES}。 */
    TOO_LARGE
  }

  /** 唯一解析入口拒绝输入时抛出的异常；保持 {@link IllegalArgumentException} 以便现有调用方捕获。 */
  public static final class InvalidOptionsException extends IllegalArgumentException {

    private final InvalidReason reason;

    private InvalidOptionsException(InvalidReason reason, String message) {
      super(message);
      this.reason = reason;
    }

    public InvalidReason reason() {
      return reason;
    }
  }

  public ProviderProtocolOptions {
    canonicalJson = canonicalize(canonicalJson);
  }

  /** 返回 canonical JSON 的独立可变副本；每次调用互不影响。 */
  public ObjectNode copyOptions() {
    try {
      return (ObjectNode) STRICT_MAPPER.readTree(canonicalJson);
    } catch (JacksonException error) {
      throw new IllegalStateException("variant protocol options are not valid JSON", error);
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
    } catch (JacksonException error) {
      throw malformed();
    }
    if (!node.isObject()) {
      throw malformed();
    }
    try {
      return STRICT_MAPPER.writeValueAsString(node);
    } catch (JacksonException error) {
      throw malformed();
    }
  }

  private static InvalidOptionsException malformed() {
    return new InvalidOptionsException(
        InvalidReason.MALFORMED, "protocolOptions must be a strict JSON object");
  }

  /** UTF-8 字节数不小于 UTF-16 字符数，因此先用字符数做零成本预筛，再按真实字节数判定。 */
  private static void requireWithinByteLimit(String json) {
    if (json.length() > MAX_UTF8_BYTES
        || json.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
      throw new InvalidOptionsException(
          InvalidReason.TOO_LARGE,
          "protocolOptions must not exceed " + MAX_UTF8_BYTES + " UTF-8 bytes");
    }
  }
}

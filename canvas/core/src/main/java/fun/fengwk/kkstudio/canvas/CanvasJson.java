package fun.fengwk.kkstudio.canvas;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 节点 Function args 的严格 JSON 值模型。
 *
 * <p>对象保留插入顺序，数组保留顺序，数值以去尾零的 {@link BigDecimal} 表示，因此 JSON 语义相等的两个值在 {@link Object#equals(Object)}
 * 下相等，可以直接作为 typed command 的前置基线比较。解析拒绝重复键、非法转义、尾随内容、字符串内控制字符、 超过 {@link #MAX_DEPTH} 的嵌套和超过 {@link
 * #MAX_LENGTH} 的输入，是 args 的唯一严格入口。
 */
public sealed interface CanvasJson
    permits CanvasJson.JsonObject,
        CanvasJson.JsonArray,
        CanvasJson.JsonText,
        CanvasJson.JsonNumber,
        CanvasJson.JsonBool,
        CanvasJson.JsonNull {

  /** 单个 args JSON 文本长度上限（字符数），用于限制节点配置大小。 */
  int MAX_LENGTH = 65_536;

  /** 解析与遍历的最大嵌套深度。 */
  int MAX_DEPTH = 16;

  /** 解析任意 JSON 值；输入非法时抛 {@link IllegalArgumentException}。 */
  static CanvasJson parse(String json) {
    return CanvasJsonCodec.parse(json);
  }

  /** 解析 JSON object；值不是 object 时抛 {@link IllegalArgumentException}。 */
  static JsonObject parseObject(String json) {
    CanvasJson value = parse(json);
    if (!(value instanceof JsonObject object)) {
      throw new IllegalArgumentException("json must be an object");
    }
    return object;
  }

  /** 压缩序列化：无多余空白，对象保留插入顺序，是持久化与比较的唯一文本形式。 */
  String write();

  /** JSON object；null 值必须用 {@link JsonNull} 表达。 */
  record JsonObject(Map<String, CanvasJson> values) implements CanvasJson {

    public JsonObject {
      Objects.requireNonNull(values, "values");
      Map<String, CanvasJson> ordered = new LinkedHashMap<>();
      for (Map.Entry<String, CanvasJson> entry : values.entrySet()) {
        ordered.put(
            Objects.requireNonNull(entry.getKey(), "object key"),
            Objects.requireNonNull(entry.getValue(), "object value"));
      }
      values = Collections.unmodifiableMap(ordered);
    }

    public static JsonObject empty() {
      return new JsonObject(Map.of());
    }

    @Override
    public String write() {
      return CanvasJsonCodec.write(this);
    }
  }

  /** JSON array。 */
  record JsonArray(List<CanvasJson> values) implements CanvasJson {

    public JsonArray {
      Objects.requireNonNull(values, "values");
      values = List.copyOf(values);
    }

    @Override
    public String write() {
      return CanvasJsonCodec.write(this);
    }
  }

  /** JSON string。 */
  record JsonText(String value) implements CanvasJson {

    public JsonText {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public String write() {
      return CanvasJsonCodec.write(this);
    }
  }

  /** JSON number；构造时去尾零，使 {@code 1} 与 {@code 1.0} 相等。 */
  record JsonNumber(BigDecimal value) implements CanvasJson {

    public JsonNumber {
      Objects.requireNonNull(value, "value");
      value = value.stripTrailingZeros();
    }

    @Override
    public String write() {
      return CanvasJsonCodec.write(this);
    }
  }

  /** JSON boolean。 */
  record JsonBool(boolean value) implements CanvasJson {

    @Override
    public String write() {
      return CanvasJsonCodec.write(this);
    }
  }

  /** JSON null。 */
  record JsonNull() implements CanvasJson {

    @Override
    public String write() {
      return CanvasJsonCodec.write(this);
    }
  }
}

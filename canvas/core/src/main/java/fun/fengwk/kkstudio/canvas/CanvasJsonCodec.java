package fun.fengwk.kkstudio.canvas;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonWriteFeature;

import fun.fengwk.kkstudio.canvas.CanvasJson.JsonArray;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonBool;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNull;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;

import java.io.Writer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CanvasJson} 基于 Jackson 3 core/stream 的严格解析与规范序列化。
 *
 * <p>词法与转义统一交给 Jackson token 流：重复键、尾随内容、非法数值/转义、控制字符与超深嵌套由解析器直接拒绝，不再自写字符级状态机。对象/数组按容器计嵌套深度，控制字符固定小写
 * hex 转义。解析成功即保证规范化文本也落在 {@link CanvasJson#MAX_LENGTH} 预算内，因此接受的值一定能写回并再次解析。
 */
final class CanvasJsonCodec {

  private static final JsonFactory FACTORY =
      JsonFactory.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          // 规范转义固定小写 hex，与历史 canonical 文本和命令指纹保持一致。
          .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(CanvasJson.MAX_DEPTH)
                  .maxNumberLength(CanvasJson.MAX_LENGTH)
                  .maxNameLength(CanvasJson.MAX_LENGTH)
                  .maxStringLength(CanvasJson.MAX_LENGTH)
                  .maxDocumentLength(CanvasJson.MAX_LENGTH)
                  .build())
          .build();

  private CanvasJsonCodec() {}

  static CanvasJson parse(String json) {
    if (json == null) {
      throw new IllegalArgumentException("json must not be null");
    }
    if (json.length() > CanvasJson.MAX_LENGTH) {
      throw new IllegalArgumentException(
          "json must not exceed " + CanvasJson.MAX_LENGTH + " characters");
    }
    CanvasJson value;
    try (JsonParser parser = FACTORY.createParser(json)) {
      JsonToken token = parser.nextToken();
      if (token == null) {
        throw new IllegalArgumentException("json value is missing");
      }
      value = readValue(parser, token);
      if (parser.nextToken() != null) {
        throw new IllegalArgumentException("json must not contain trailing content");
      }
    } catch (JacksonException error) {
      throw new IllegalArgumentException("json is invalid: " + error.getMessage(), error);
    }
    // 读写闭合：写回文本同样受长度预算约束，短输入展开出的越界 canonical（如 1e65536）在此被拒绝。
    write(value);
    return value;
  }

  static String write(CanvasJson value) {
    BoundedWriter writer = new BoundedWriter();
    try (JsonGenerator generator = FACTORY.createGenerator(ObjectWriteContext.empty(), writer)) {
      writeValue(generator, value, 0);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("json is invalid: " + error.getMessage(), error);
    }
    return writer.content();
  }

  private static CanvasJson readValue(JsonParser parser, JsonToken token) {
    return switch (token) {
      case START_OBJECT -> readObject(parser);
      case START_ARRAY -> readArray(parser);
      case VALUE_STRING -> new JsonText(parser.getString());
      case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new JsonNumber(parser.getDecimalValue());
      case VALUE_TRUE -> new JsonBool(true);
      case VALUE_FALSE -> new JsonBool(false);
      case VALUE_NULL -> new JsonNull();
      default -> throw new IllegalArgumentException("json value is invalid");
    };
  }

  private static JsonObject readObject(JsonParser parser) {
    Map<String, CanvasJson> values = new LinkedHashMap<>();
    JsonToken token = parser.nextToken();
    while (token != JsonToken.END_OBJECT) {
      if (token != JsonToken.PROPERTY_NAME) {
        throw new IllegalArgumentException("json object key must be a string");
      }
      values.put(parser.currentName(), readValue(parser, parser.nextToken()));
      token = parser.nextToken();
    }
    return new JsonObject(values);
  }

  private static JsonArray readArray(JsonParser parser) {
    List<CanvasJson> values = new ArrayList<>();
    JsonToken token = parser.nextToken();
    while (token != JsonToken.END_ARRAY) {
      values.add(readValue(parser, token));
      token = parser.nextToken();
    }
    return new JsonArray(values);
  }

  private static void writeValue(JsonGenerator generator, CanvasJson value, int depth) {
    switch (value) {
      case JsonObject object -> {
        requireContainerDepth(depth);
        generator.writeStartObject();
        for (Map.Entry<String, CanvasJson> entry : object.values().entrySet()) {
          generator.writeName(entry.getKey());
          writeValue(generator, entry.getValue(), depth + 1);
        }
        generator.writeEndObject();
      }
      case JsonArray array -> {
        requireContainerDepth(depth);
        generator.writeStartArray();
        for (CanvasJson item : array.values()) {
          writeValue(generator, item, depth + 1);
        }
        generator.writeEndArray();
      }
      case JsonText text -> generator.writeString(text.value());
      case JsonNumber number -> writeNumber(generator, number.value());
      case JsonBool bool -> generator.writeBoolean(bool.value());
      case JsonNull ignored -> generator.writeNull();
    }
  }

  /** 嵌套深度按容器计数，与解析器的 maxNestingDepth 语义一致，避免手工构造绕过解析限制。 */
  private static void requireContainerDepth(int depth) {
    if (depth >= CanvasJson.MAX_DEPTH) {
      throw new IllegalArgumentException("json nesting must not exceed " + CanvasJson.MAX_DEPTH);
    }
  }

  /**
   * 在预算内把 decimal 明文交给生成器写出：先核验 {@link BigDecimal#toPlainString()} 的字符数，只有不超过 {@link
   * CanvasJson#MAX_LENGTH} 时才实际展开，既不依赖 Jackson 对 plain BigDecimal scale 的人为限制，也不产生越界大字符串。
   */
  private static void writeNumber(JsonGenerator generator, BigDecimal value) {
    BigDecimal plain = value.stripTrailingZeros();
    if (plainLength(plain) > CanvasJson.MAX_LENGTH) {
      throw new IllegalArgumentException(
          "json number must not exceed " + CanvasJson.MAX_LENGTH + " characters");
    }
    generator.writeNumber(plain.toPlainString());
  }

  /** 计算 {@link BigDecimal#toPlainString()} 的字符数，不实际展开大字符串。 */
  private static long plainLength(BigDecimal value) {
    int precision = value.precision();
    int scale = value.scale();
    long length;
    if (scale <= 0) {
      length = (long) precision - scale;
    } else if (precision > scale) {
      length = (long) precision + 1;
    } else {
      length = 2L + scale;
    }
    return value.signum() < 0 ? length + 1 : length;
  }

  /** 累计输出长度受 {@link CanvasJson#MAX_LENGTH} 约束的 writer，避免在越界前先拼出完整大字符串。 */
  private static final class BoundedWriter extends Writer {

    private final StringBuilder buffer = new StringBuilder();
    private int length;

    @Override
    public void write(char[] characters, int offset, int count) {
      if (length + count > CanvasJson.MAX_LENGTH) {
        throw new IllegalArgumentException(
            "json must not exceed " + CanvasJson.MAX_LENGTH + " characters");
      }
      buffer.append(characters, offset, count);
      length += count;
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    private String content() {
      return buffer.toString();
    }
  }
}

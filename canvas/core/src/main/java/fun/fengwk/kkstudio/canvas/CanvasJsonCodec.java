package fun.fengwk.kkstudio.canvas;

import fun.fengwk.kkstudio.canvas.CanvasJson.JsonArray;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonBool;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNull;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link CanvasJson} 的严格解析与压缩序列化。 */
final class CanvasJsonCodec {

  private CanvasJsonCodec() {}

  static CanvasJson parse(String json) {
    if (json == null) {
      throw new IllegalArgumentException("json must not be null");
    }
    if (json.length() > CanvasJson.MAX_LENGTH) {
      throw new IllegalArgumentException(
          "json must not exceed " + CanvasJson.MAX_LENGTH + " characters");
    }
    Reader reader = new Reader(json);
    CanvasJson value = reader.readValue(0);
    reader.requireEnd();
    return value;
  }

  static String write(CanvasJson value) {
    StringBuilder builder = new StringBuilder();
    writeValue(builder, value, 0);
    return builder.toString();
  }

  private static void writeValue(StringBuilder builder, CanvasJson value, int depth) {
    if (depth > CanvasJson.MAX_DEPTH) {
      throw new IllegalArgumentException("json nesting must not exceed " + CanvasJson.MAX_DEPTH);
    }
    switch (value) {
      case JsonObject object -> {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<String, CanvasJson> entry : object.values().entrySet()) {
          if (!first) {
            builder.append(',');
          }
          first = false;
          writeText(builder, entry.getKey());
          builder.append(':');
          writeValue(builder, entry.getValue(), depth + 1);
        }
        builder.append('}');
      }
      case JsonArray array -> {
        builder.append('[');
        boolean first = true;
        for (CanvasJson item : array.values()) {
          if (!first) {
            builder.append(',');
          }
          first = false;
          writeValue(builder, item, depth + 1);
        }
        builder.append(']');
      }
      case JsonText text -> writeText(builder, text.value());
      case JsonNumber number -> builder.append(number.value().toPlainString());
      case JsonBool bool -> builder.append(bool.value() ? "true" : "false");
      case JsonNull ignored -> builder.append("null");
    }
  }

  private static void writeText(StringBuilder builder, String value) {
    builder.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\b' -> builder.append("\\b");
        case '\f' -> builder.append("\\f");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (character < 0x20) {
            builder.append("\\u").append(String.format("%04x", (int) character));
          } else {
            builder.append(character);
          }
        }
      }
    }
    builder.append('"');
  }

  /** 严格 JSON reader：拒绝重复键、控制字符、非法转义、尾随内容与超深嵌套。 */
  private static final class Reader {

    private final String source;
    private int position;

    private Reader(String source) {
      this.source = source;
    }

    private CanvasJson readValue(int depth) {
      if (depth > CanvasJson.MAX_DEPTH) {
        throw invalid("json nesting must not exceed " + CanvasJson.MAX_DEPTH);
      }
      skipWhitespace();
      if (position >= source.length()) {
        throw invalid("json value is missing");
      }
      char character = source.charAt(position);
      switch (character) {
        case '{':
          return readObject(depth);
        case '[':
          return readArray(depth);
        case '"':
          return new JsonText(readText());
        case 't':
          expect("true");
          return new JsonBool(true);
        case 'f':
          expect("false");
          return new JsonBool(false);
        case 'n':
          expect("null");
          return new JsonNull();
        default:
          return readNumber();
      }
    }

    private JsonObject readObject(int depth) {
      position++;
      skipWhitespace();
      if (peek() == '}') {
        position++;
        return JsonObject.empty();
      }
      Map<String, CanvasJson> values = new LinkedHashMap<>();
      while (true) {
        skipWhitespace();
        if (peek() != '"') {
          throw invalid("json object key must be a string");
        }
        String key = readText();
        if (values.containsKey(key)) {
          throw invalid("json object must not repeat key " + key);
        }
        skipWhitespace();
        if (peek() != ':') {
          throw invalid("json object key must be followed by ':'");
        }
        position++;
        values.put(key, readValue(depth + 1));
        skipWhitespace();
        char separator = peek();
        if (separator == ',') {
          position++;
          continue;
        }
        if (separator == '}') {
          position++;
          return new JsonObject(values);
        }
        throw invalid("json object must be closed with '}'");
      }
    }

    private JsonArray readArray(int depth) {
      position++;
      skipWhitespace();
      if (peek() == ']') {
        position++;
        return new JsonArray(List.of());
      }
      List<CanvasJson> values = new ArrayList<>();
      while (true) {
        values.add(readValue(depth + 1));
        skipWhitespace();
        char separator = peek();
        if (separator == ',') {
          position++;
          continue;
        }
        if (separator == ']') {
          position++;
          return new JsonArray(values);
        }
        throw invalid("json array must be closed with ']'");
      }
    }

    private String readText() {
      position++;
      StringBuilder builder = new StringBuilder();
      while (true) {
        if (position >= source.length()) {
          throw invalid("json string must be closed");
        }
        char character = source.charAt(position);
        if (character == '"') {
          position++;
          return builder.toString();
        }
        if (character == '\\') {
          builder.append(readEscape());
          continue;
        }
        if (character < 0x20) {
          throw invalid("json string must not contain control characters");
        }
        builder.append(character);
        position++;
      }
    }

    private char readEscape() {
      position++;
      if (position >= source.length()) {
        throw invalid("json escape must not end the input");
      }
      char escaped = source.charAt(position);
      position++;
      switch (escaped) {
        case '"':
          return '"';
        case '\\':
          return '\\';
        case '/':
          return '/';
        case 'b':
          return '\b';
        case 'f':
          return '\f';
        case 'n':
          return '\n';
        case 'r':
          return '\r';
        case 't':
          return '\t';
        case 'u':
          return readUnicodeEscape();
        default:
          throw invalid("json escape must be a valid escape sequence");
      }
    }

    private char readUnicodeEscape() {
      if (position + 4 > source.length()) {
        throw invalid("json unicode escape must contain four hex digits");
      }
      String digits = source.substring(position, position + 4);
      for (int index = 0; index < digits.length(); index++) {
        if (Character.digit(digits.charAt(index), 16) < 0) {
          throw invalid("json unicode escape must contain four hex digits");
        }
      }
      position += 4;
      return (char) Integer.parseInt(digits, 16);
    }

    private CanvasJson readNumber() {
      int start = position;
      if (peek() == '-') {
        position++;
      }
      if (peek() == '0' && Character.isDigit(peek(1))) {
        throw invalid("json number must not contain leading zeros");
      }
      readDigits("json number must contain an integer part");
      if (peek() == '.') {
        position++;
        readDigits("json number must contain a fraction part");
      }
      if (peek() == 'e' || peek() == 'E') {
        position++;
        if (peek() == '+' || peek() == '-') {
          position++;
        }
        readDigits("json number must contain an exponent part");
      }
      String literal = source.substring(start, position);
      try {
        return new JsonNumber(new BigDecimal(literal));
      } catch (NumberFormatException error) {
        throw invalid("json number is invalid");
      }
    }

    private void readDigits(String message) {
      int digits = 0;
      while (position < source.length() && Character.isDigit(source.charAt(position))) {
        position++;
        digits++;
      }
      if (digits == 0) {
        throw invalid(message);
      }
    }

    private void expect(String literal) {
      if (!source.startsWith(literal, position)) {
        throw invalid("json literal is invalid");
      }
      position += literal.length();
    }

    private void skipWhitespace() {
      while (position < source.length()) {
        char character = source.charAt(position);
        if (character == ' ' || character == '\t' || character == '\n' || character == '\r') {
          position++;
          continue;
        }
        return;
      }
    }

    private char peek() {
      return peek(0);
    }

    private char peek(int offset) {
      int index = position + offset;
      return index < source.length() ? source.charAt(index) : '\0';
    }

    private void requireEnd() {
      skipWhitespace();
      if (position != source.length()) {
        throw invalid("json must not contain trailing content");
      }
    }

    private IllegalArgumentException invalid(String message) {
      return new IllegalArgumentException(message + " at index " + position);
    }
  }
}

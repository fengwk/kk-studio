package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;

/**
 * 反向数据面上的一条 header（允许同名字段出现多次，保持到达顺序）。
 *
 * <p>header 名必须是合法 HTTP token，值不得包含 CR/LF，也不得有控制字符：这是阻止 header 注入到上游/浏览器的 wire 边界防御。
 */
public record IdeHeader(String name, String value) {

  public IdeHeader {
    name = requireHeaderName(name);
    value = Objects.requireNonNull(value, "value");
    if (containsControlCharacters(value)) {
      throw new IllegalArgumentException("header value must not contain control characters");
    }
  }

  /** header 名限制为 RFC 9110 token：仅 ASCII 可见字符且不含分隔符。 */
  static String requireHeaderName(String value) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("header name must not be empty");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean tokenChar =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '!'
              || c == '#'
              || c == '$'
              || c == '%'
              || c == '&'
              || c == '\''
              || c == '*'
              || c == '+'
              || c == '-'
              || c == '.'
              || c == '^'
              || c == '_'
              || c == '`'
              || c == '|'
              || c == '~';
      if (!tokenChar) {
        throw new IllegalArgumentException("header name contains an invalid token character");
      }
    }
    return value;
  }

  static boolean containsControlCharacters(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        return true;
      }
    }
    return false;
  }
}

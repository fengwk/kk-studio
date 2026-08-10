package fun.fengwk.kkstudio.harness.daemon.coding;

import java.util.Objects;
import java.util.regex.Pattern;

/** 将 coding tools 使用的 POSIX glob 转换为与平台无关的 Java regex。 */
final class GlobPattern {

  private final Pattern pattern;

  private GlobPattern(Pattern pattern) {
    this.pattern = pattern;
  }

  static GlobPattern compile(String glob) {
    Objects.requireNonNull(glob, "glob");
    return new GlobPattern(Pattern.compile("^" + toRegex(glob) + "$"));
  }

  boolean matches(String path) {
    return pattern.matcher(path).matches();
  }

  private static String toRegex(String glob) {
    StringBuilder regex = new StringBuilder();
    for (int index = 0; index < glob.length(); index++) {
      char current = glob.charAt(index);
      if (current == '\\') {
        if (index + 1 < glob.length()) {
          appendLiteral(regex, glob.charAt(++index));
        } else {
          appendLiteral(regex, current);
        }
      } else if (current == '*') {
        int end = index + 1;
        while (end < glob.length() && glob.charAt(end) == '*') {
          end++;
        }
        boolean completeDoubleStar =
            end - index == 2
                && (index == 0 || glob.charAt(index - 1) == '/')
                && (end == glob.length() || glob.charAt(end) == '/');
        if (completeDoubleStar && end < glob.length()) {
          regex.append("(?:[^/]+/)*");
          index = end;
        } else if (completeDoubleStar) {
          regex.append(".*");
          index = end - 1;
        } else {
          regex.append("[^/]*");
          index = end - 1;
        }
      } else if (current == '?') {
        regex.append("[^/]");
      } else if (current == '[') {
        index = appendCharacterClass(regex, glob, index);
      } else {
        appendLiteral(regex, current);
      }
    }
    return regex.toString();
  }

  private static int appendCharacterClass(StringBuilder regex, String glob, int start) {
    int end = start + 1;
    if (end < glob.length() && (glob.charAt(end) == '!' || glob.charAt(end) == '^')) {
      end++;
    }
    if (end < glob.length() && glob.charAt(end) == ']') {
      end++;
    }
    boolean escaped = false;
    while (end < glob.length()) {
      char current = glob.charAt(end);
      if (!escaped && current == ']') {
        break;
      }
      escaped = !escaped && current == '\\';
      if (current != '\\') {
        escaped = false;
      }
      end++;
    }
    if (end >= glob.length()) {
      appendLiteral(regex, '[');
      return start;
    }

    regex.append("(?!/)[");
    int index = start + 1;
    if (index < end && (glob.charAt(index) == '!' || glob.charAt(index) == '^')) {
      regex.append('^');
      index++;
    }
    if (index < end && glob.charAt(index) == ']') {
      regex.append("\\]");
      index++;
    }
    for (; index < end; index++) {
      char current = glob.charAt(index);
      if (current == '\\' && index + 1 < end) {
        appendEscapedCharacterClassLiteral(regex, glob.charAt(++index));
      } else if (current == '[' || current == '&') {
        regex.append('\\').append(current);
      } else {
        regex.append(current);
      }
    }
    regex.append(']');
    return end;
  }

  /** glob 字符类内的反斜杠始终把下一字符转成字面量，不能透传成 Java regex 的 {@code \d}/{@code \n} 等转义。 */
  private static void appendEscapedCharacterClassLiteral(StringBuilder regex, char value) {
    if ("\\^-[]&".indexOf(value) >= 0) {
      regex.append('\\');
    }
    regex.append(value);
  }

  private static void appendLiteral(StringBuilder regex, char value) {
    if ("\\.^$|(){}+*?[]".indexOf(value) >= 0) {
      regex.append('\\');
    }
    regex.append(value);
  }
}

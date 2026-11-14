package fun.fengwk.kkstudio.platform.harness.tool.permission;

import java.util.List;

/**
 * 可执行位置的 token 检查：判断该词是否会被 Shell 在运行期展开，或携带重定向。
 *
 * <p>四项检测共享同一套引号状态机，差异只在“哪种字符在哪种引号状态下算命中”：检测型扫描不产出文本，因此跳过字符的方式一致。
 */
final class BashExecutableToken {

  private BashExecutableToken() {}

  /** token 的可执行位置是否会被运行期展开（未加引号的 {@code $} 或双引号内的 {@code $}）。 */
  static boolean hasExpansion(String token) {
    return detects(token, '$', false);
  }

  /** token 的可执行位置是否携带输入/输出重定向。 */
  static boolean hasRedirection(String token) {
    return detects(token, '<', true) || detects(token, '>', true);
  }

  /**
   * 判断 token 是否在指定引号状态下出现 {@code target}。
   *
   * @param target 命中字符
   * @param unquotedOnly 为 true 时只在未加引号状态下命中；否则双引号内的命中也算
   */
  private static boolean detects(String token, char target, boolean unquotedOnly) {
    Quote quote = null;
    for (int i = 0; i < token.length(); i++) {
      char ch = token.charAt(i);
      Character next = i + 1 < token.length() ? token.charAt(i + 1) : null;
      if (quote == Quote.SINGLE) {
        if (ch == '\'') {
          quote = null;
        }
        continue;
      }
      if (quote == Quote.DOUBLE) {
        if (ch == '\\' && next != null) {
          i++;
          continue;
        }
        if (ch == '"') {
          quote = null;
        } else if (ch == target && !unquotedOnly) {
          return true;
        }
        continue;
      }
      if (ch == '\\' && next != null) {
        i++;
      } else if (ch == '\'') {
        quote = Quote.SINGLE;
      } else if (ch == '"') {
        quote = Quote.DOUBLE;
      } else if (ch == target) {
        return true;
      }
    }
    return false;
  }

  /**
   * 去掉命令名 token 的引号与转义，并只保留 basename。
   *
   * <p>双引号内只有 {@code $`"\} 与换行前的反斜杠是转义，其余反斜杠按字面保留；未加引号的反斜杠一律转义下一个字符。
   */
  static String executableName(String token) {
    StringBuilder value = new StringBuilder();
    Quote quote = null;
    for (int i = 0; i < token.length(); i++) {
      char ch = token.charAt(i);
      Character next = i + 1 < token.length() ? token.charAt(i + 1) : null;
      if (quote == Quote.SINGLE) {
        if (ch == '\'') {
          quote = null;
        } else {
          value.append(ch);
        }
        continue;
      }
      if (quote == Quote.DOUBLE) {
        if (ch == '\\' && next != null && isDoubleQuoteEscape(next)) {
          value.append(next);
          i++;
        } else if (ch == '"') {
          quote = null;
        } else {
          value.append(ch);
        }
        continue;
      }
      if (ch == '\\' && next != null) {
        value.append(next);
        i++;
      } else if (ch == '\'') {
        quote = Quote.SINGLE;
      } else if (ch == '"') {
        quote = Quote.DOUBLE;
      } else {
        value.append(ch);
      }
    }
    String normalized = value.toString();
    int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
    return slash >= 0 ? normalized.substring(slash + 1) : normalized;
  }

  /** 双引号内可转义的字符集。 */
  static boolean isDoubleQuoteEscape(char ch) {
    return "$`\"\\\n".indexOf(ch) >= 0;
  }

  /** 命令段是否以复合语法开头：关键字、子 shell、分组或函数定义。 */
  static boolean isCompoundShellSyntax(List<String> tokens, int commandIndex, String executable) {
    String token = tokens.get(commandIndex);
    if (BashSurfaceAnalyzer.COMPOUND_SHELL_KEYWORDS.contains(executable)
        || token.startsWith("(") && !token.startsWith("((")
        || token.startsWith("{")
        || token.matches("^[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\).*$")) {
      return true;
    }
    return token.matches("^[A-Za-z_][A-Za-z0-9_]*$")
        && commandIndex + 1 < tokens.size()
        && tokens.get(commandIndex + 1).startsWith("()");
  }
}

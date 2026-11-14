package fun.fengwk.kkstudio.platform.harness.tool.permission;

import java.util.ArrayList;
import java.util.List;

/**
 * 单段命令的 token 化：保留引号、转义与括号深度信息，去掉真正的注释与续行。
 *
 * <p>tokenizer 不理解运行时展开，因此 quoted / nested 词各自成为一个 token；它也不判定动态执行面，那是 {@link BashSurfaceAnalyzer}
 * 的职责。
 */
final class BashTokenizer {

  private BashTokenizer() {}

  static List<String> tokenize(String segment) {
    String input = BashCommandText.normalize(segment);
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    Quote quote = null;
    int parenDepth = 0;
    int braceDepth = 0;
    int doubleBracketDepth = 0;
    boolean inComment = false;
    for (int i = 0; i < input.length(); i++) {
      char ch = input.charAt(i);
      Character next = i + 1 < input.length() ? input.charAt(i + 1) : null;

      if (inComment) {
        if (ch == '\n') {
          inComment = false;
        }
        continue;
      }
      if (quote == Quote.SINGLE) {
        current.append(ch);
        if (ch == '\'') {
          quote = null;
        }
        continue;
      }
      if (quote == Quote.DOUBLE || quote == Quote.BACKTICK) {
        current.append(ch);
        if (ch == '\\' && next != null) {
          current.append(next);
          i++;
          continue;
        }
        if (quote == Quote.DOUBLE && ch == '"' || quote == Quote.BACKTICK && ch == '`') {
          quote = null;
        }
        continue;
      }
      if (ch == '#' && current.length() == 0) {
        inComment = true;
        continue;
      }
      if (ch == '\\' && next != null) {
        current.append(ch).append(next);
        i++;
        continue;
      }
      if (ch == '\'') {
        quote = Quote.SINGLE;
        current.append(ch);
        continue;
      }
      if (ch == '"') {
        quote = Quote.DOUBLE;
        current.append(ch);
        continue;
      }
      if (ch == '`') {
        quote = Quote.BACKTICK;
        current.append(ch);
        continue;
      }
      if (ch == '[' && next != null && next == '[') {
        doubleBracketDepth++;
        current.append("[[");
        i++;
        continue;
      }
      if (doubleBracketDepth > 0 && ch == ']' && next != null && next == ']') {
        doubleBracketDepth--;
        current.append("]]");
        i++;
        continue;
      }
      if (ch == '(') {
        parenDepth++;
        current.append(ch);
        continue;
      }
      if (ch == ')' && parenDepth > 0) {
        parenDepth--;
        current.append(ch);
        continue;
      }
      if (ch == '{') {
        braceDepth++;
        current.append(ch);
        continue;
      }
      if (ch == '}' && braceDepth > 0) {
        braceDepth--;
        current.append(ch);
        continue;
      }
      if (Character.isWhitespace(ch)
          && parenDepth == 0
          && braceDepth == 0
          && doubleBracketDepth == 0) {
        pushToken(tokens, current);
        continue;
      }
      current.append(ch);
    }
    pushToken(tokens, current);
    return List.copyOf(tokens);
  }

  private static void pushToken(List<String> tokens, StringBuilder current) {
    if (current.length() > 0) {
      tokens.add(current.toString());
      current.setLength(0);
    }
  }
}

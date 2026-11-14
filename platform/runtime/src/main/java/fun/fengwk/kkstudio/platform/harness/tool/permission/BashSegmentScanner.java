package fun.fengwk.kkstudio.platform.harness.tool.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 顶层命令段扫描：切分确定性的静态命令段，并在遇到无法静态确定的语法时给出放弃原因。
 *
 * <p>扫描只做词法判定，不启动 Shell、不展开变量。引号、转义、括号深度、顶层控制运算符与 heredoc 边界都在这里处理；命令替换、进程替换、 未匹配的闭合符号与未闭合结构会返回
 * {@code unsupported}，由权限层按完整原命令保守判定。
 *
 * <p>放弃时报告的段列表只包含此刻已完成的段：结构未闭合时当前未完成的段不会被补入，保证调用方看到的是“能被静态确认的部分”。
 */
final class BashSegmentScanner {

  private BashSegmentScanner() {}

  /** 扫描结果：{@code reason} 非空表示放弃静态分析，否则 {@code segments} 是全部静态段。 */
  record ScanResult(List<String> segments, String reason) {}

  static ScanResult scan(String input) {
    List<String> segments = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    Quote quote = null;
    int parenDepth = 0;
    int braceDepth = 0;
    int doubleBracketDepth = 0;
    boolean inComment = false;
    List<BashHeredoc.PendingHeredoc> pendingHeredocs = new ArrayList<>();

    for (int i = 0; i < input.length(); i++) {
      char ch = input.charAt(i);
      Character next = i + 1 < input.length() ? input.charAt(i + 1) : null;

      if (inComment) {
        if (ch == '\n') {
          inComment = false;
          if (parenDepth == 0 && braceDepth == 0 && doubleBracketDepth == 0) {
            pushCurrent(segments, current, pendingHeredocs);
            BashHeredoc.ConsumeResult consumed =
                BashHeredoc.consumePending(input, i, segments, pendingHeredocs);
            if (consumed.reason() != null) {
              return unsupported(consumed.reason(), segments);
            }
            i = consumed.nextIndex() - 1;
          } else {
            current.append('\n');
          }
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
      if (quote == Quote.DOUBLE) {
        current.append(ch);
        if (ch == '\\' && next != null) {
          current.append(next);
          i++;
          continue;
        }
        if (BashCommandText.isCommandSubstitutionStart(input, i) || ch == '`') {
          return unsupported("command_substitution", segments);
        }
        if (ch == '"') {
          quote = null;
        }
        continue;
      }

      if (ch == '#' && BashCommandText.isCommentStart(current)) {
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
      if (BashCommandText.isCommandSubstitutionStart(input, i) || ch == '`') {
        return unsupported("command_substitution", segments);
      }
      if ((ch == '<' || ch == '>') && next != null && next == '(') {
        return unsupported("process_substitution", segments);
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
      if (ch == ')') {
        if (parenDepth == 0) {
          return unsupported("unmatched_closing_paren", segments);
        }
        parenDepth--;
        current.append(ch);
        continue;
      }
      if (ch == '{') {
        braceDepth++;
        current.append(ch);
        continue;
      }
      if (ch == '}') {
        if (braceDepth == 0) {
          return unsupported("unmatched_closing_brace", segments);
        }
        braceDepth--;
        current.append(ch);
        continue;
      }
      if (parenDepth == 0 && braceDepth == 0 && doubleBracketDepth == 0) {
        int operatorLength = topLevelOperatorLength(input, i);
        if (operatorLength > 0) {
          pushCurrent(segments, current, pendingHeredocs);
          if (ch == '\n' && !pendingHeredocs.isEmpty()) {
            BashHeredoc.ConsumeResult consumed =
                BashHeredoc.consumePending(input, i, segments, pendingHeredocs);
            if (consumed.reason() != null) {
              return unsupported(consumed.reason(), segments);
            }
            i = consumed.nextIndex() - 1;
            continue;
          }
          i += operatorLength - 1;
          continue;
        }
      }
      current.append(ch);
    }

    if (quote != null) {
      return unsupported("unclosed_" + quote.name().toLowerCase(Locale.ROOT) + "_quote", segments);
    }
    if (parenDepth > 0) {
      return unsupported("unclosed_paren", segments);
    }
    if (braceDepth > 0) {
      return unsupported("unclosed_brace", segments);
    }
    if (doubleBracketDepth > 0) {
      return unsupported("unclosed_double_bracket", segments);
    }
    pushSegment(segments, current.toString());
    return new ScanResult(List.copyOf(segments), null);
  }

  private static ScanResult unsupported(String reason, List<String> segments) {
    return new ScanResult(List.copyOf(segments), reason);
  }

  private static void pushCurrent(
      List<String> segments,
      StringBuilder current,
      List<BashHeredoc.PendingHeredoc> pendingHeredocs) {
    String trimmed = current.toString().trim();
    current.setLength(0);
    if (trimmed.isEmpty()) {
      return;
    }
    List<BashHeredoc.HeredocDelimiter> delimiters = BashHeredoc.extractDelimiters(trimmed);
    int segmentIndex = segments.size();
    segments.add(trimmed);
    if (!delimiters.isEmpty()) {
      pendingHeredocs.add(new BashHeredoc.PendingHeredoc(segmentIndex, delimiters));
    }
  }

  private static void pushSegment(List<String> segments, String value) {
    String trimmed = value.trim();
    if (!trimmed.isEmpty()) {
      segments.add(trimmed);
    }
  }

  /** 识别顶层控制运算符，同时排除 {@code &>}、{@code >&}、{@code >|} 等重定向。 */
  private static int topLevelOperatorLength(String input, int index) {
    char ch = input.charAt(index);
    Character next = index + 1 < input.length() ? input.charAt(index + 1) : null;
    Character previous = index > 0 ? input.charAt(index - 1) : null;
    if (ch == '&' && next != null && next == '&') {
      return 2;
    }
    if (ch == '&'
        && (next == null || next != '>')
        && (previous == null || previous != '>' && previous != '<')) {
      return 1;
    }
    if (ch == '|' && previous != null && previous == '>') {
      return 0;
    }
    if (ch == '|' && next != null && (next == '|' || next == '&')) {
      return 2;
    }
    return ch == '|' || ch == ';' || ch == '\n' ? 1 : 0;
  }
}

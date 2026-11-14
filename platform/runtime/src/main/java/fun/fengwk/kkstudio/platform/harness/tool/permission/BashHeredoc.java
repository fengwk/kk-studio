package fun.fengwk.kkstudio.platform.harness.tool.permission;

import java.util.ArrayList;
import java.util.List;

/**
 * heredoc 的边界识别与正文消费：把正文并入拥有它的命令段，并拒绝允许展开的正文中的命令替换。
 *
 * <p>只有允许展开（delimiter 未被引号或反斜杠保护）的 heredoc 才需要检查正文；受保护的正文原样保留，绝不按 Shell 展开解读。
 */
final class BashHeredoc {

  private BashHeredoc() {}

  /** 从一个命令行提取 heredoc delimiter，供其后的换行消费正文。 */
  static List<HeredocDelimiter> extractDelimiters(String commandLine) {
    List<String> tokens = BashTokenizer.tokenize(commandLine);
    List<HeredocDelimiter> delimiters = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      String raw = null;
      boolean stripTabs = false;
      if (token.equals("<<") || token.equals("<<-")) {
        if (++i < tokens.size()) {
          raw = tokens.get(i);
        }
        stripTabs = token.equals("<<-");
      } else if (token.startsWith("<<-") && !token.startsWith("<<<")) {
        raw = token.substring(3);
        stripTabs = true;
      } else if (token.startsWith("<<") && !token.startsWith("<<<")) {
        raw = token.substring(2);
      }
      if (raw == null) {
        continue;
      }
      ParsedDelimiter parsed = parseDelimiter(raw);
      if (!parsed.value().isEmpty()) {
        delimiters.add(new HeredocDelimiter(parsed.value(), stripTabs, parsed.allowExpansion()));
      }
    }
    return delimiters;
  }

  /**
   * 消费全部待处理的 heredoc 正文，并把它拼回对应的命令段。
   *
   * <p>多个 heredoc 按声明顺序消费，后一个从前一个结束处开始；返回的 {@code nextIndex} 是正文之后的位置。
   */
  static ConsumeResult consumePending(
      String input, int newlineIndex, List<String> segments, List<PendingHeredoc> pendingHeredocs) {
    int currentNewlineIndex = newlineIndex;
    for (PendingHeredoc pending : pendingHeredocs) {
      ConsumeResult consumed = consumeBodies(input, currentNewlineIndex, pending.delimiters());
      if (consumed.reason() != null) {
        return consumed;
      }
      segments.set(
          pending.segmentIndex(), (segments.get(pending.segmentIndex()) + consumed.text()).trim());
      currentNewlineIndex = consumed.nextIndex() - 1;
    }
    pendingHeredocs.clear();
    return new ConsumeResult(currentNewlineIndex + 1, null, null);
  }

  private static ConsumeResult consumeBodies(
      String input, int newlineIndex, List<HeredocDelimiter> delimiters) {
    int cursor = newlineIndex + 1;
    StringBuilder text = new StringBuilder("\n");
    for (HeredocDelimiter delimiter : delimiters) {
      boolean found = false;
      while (cursor <= input.length()) {
        int lineEnd = input.indexOf('\n', cursor);
        boolean hasNewline = lineEnd != -1;
        int end = hasNewline ? lineEnd : input.length();
        String line = input.substring(cursor, end);
        text.append(input, cursor, hasNewline ? lineEnd + 1 : end);
        cursor = hasNewline ? lineEnd + 1 : end;
        String comparable = delimiter.stripLeadingTabs() ? line.replaceFirst("^\\t+", "") : line;
        if (comparable.equals(delimiter.value())) {
          found = true;
          break;
        }
        if (delimiter.allowExpansion() && BashCommandText.hasExpandableCommandSubstitution(line)) {
          return new ConsumeResult(0, "command_substitution", null);
        }
        if (!hasNewline) {
          break;
        }
      }
      if (!found) {
        return new ConsumeResult(0, "unterminated_heredoc", null);
      }
    }
    return new ConsumeResult(cursor, null, text.toString());
  }

  /**
   * 按 Shell 的 heredoc 词法去引号：任何未转义的引号与反斜杠都表示正文不做展开。
   *
   * <p>双引号内只有可转义字符前的反斜杠被吞掉，其余反斜杠按字面保留——这与命令名的去引号规则不同。
   */
  private static ParsedDelimiter parseDelimiter(String token) {
    StringBuilder value = new StringBuilder();
    Quote quote = null;
    boolean allowExpansion = true;
    for (int i = 0; i < token.length(); i++) {
      char ch = token.charAt(i);
      Character next = i + 1 < token.length() ? token.charAt(i + 1) : null;
      if (ch == '\\' && next != null && quote != Quote.SINGLE) {
        allowExpansion = false;
        if (quote == Quote.DOUBLE && !BashExecutableToken.isDoubleQuoteEscape(next)) {
          value.append(ch);
          continue;
        }
        value.append(next);
        i++;
        continue;
      }
      if (ch == '\'' && quote != Quote.DOUBLE) {
        allowExpansion = false;
        quote = quote == Quote.SINGLE ? null : Quote.SINGLE;
        continue;
      }
      if (ch == '"' && quote != Quote.SINGLE) {
        allowExpansion = false;
        quote = quote == Quote.DOUBLE ? null : Quote.DOUBLE;
        continue;
      }
      value.append(ch);
    }
    return new ParsedDelimiter(value.toString(), allowExpansion);
  }

  /** 一个声明了 {@code <<}/{@code <<-} 的命令段及其待消费的 delimiter。 */
  record PendingHeredoc(int segmentIndex, List<HeredocDelimiter> delimiters) {}

  /** 单个 heredoc delimiter：匹配值、是否 strip-tabs，以及正文是否允许展开。 */
  record HeredocDelimiter(String value, boolean stripLeadingTabs, boolean allowExpansion) {}

  private record ParsedDelimiter(String value, boolean allowExpansion) {}

  /** 正文消费结果：{@code nextIndex} 为正文之后的位置，{@code reason} 非空表示静态分析必须放弃。 */
  record ConsumeResult(int nextIndex, String reason, String text) {}
}

package fun.fengwk.kkstudio.harness.runtime.permission;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 不调用 shell 的 Bash 顶层静态 surface analyzer。动态执行面返回 unsupported，由 PermissionEvaluator 采用“完整命令显式
 * deny，否则 ask”。
 */
public final class BashSurfaceAnalyzer {
  private static final Pattern ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(?:\\+)?=");
  private static final Set<String> DYNAMIC_SHELL_WRAPPERS =
      Set.of("sh", "bash", "dash", "zsh", "ksh", "ksh93", "mksh", "fish");
  private static final Set<String> COMMAND_LAUNCHERS = Set.of("command", "env", "exec", "nohup");
  private static final Set<String> COMPOUND_SHELL_KEYWORDS =
      Set.of(
          "!",
          "case",
          "coproc",
          "do",
          "done",
          "elif",
          "else",
          "esac",
          "fi",
          "for",
          "function",
          "if",
          "select",
          "then",
          "time",
          "until",
          "while");

  public Analysis analyze(String command) {
    String input = normalize(command);
    List<String> segments = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    Quote quote = null;
    int parenDepth = 0;
    int braceDepth = 0;
    int doubleBracketDepth = 0;
    boolean inComment = false;
    List<PendingHeredoc> pendingHeredocs = new ArrayList<>();

    for (int i = 0; i < input.length(); i++) {
      char ch = input.charAt(i);
      Character next = i + 1 < input.length() ? input.charAt(i + 1) : null;

      if (inComment) {
        if (ch == '\n') {
          inComment = false;
          if (parenDepth == 0 && braceDepth == 0 && doubleBracketDepth == 0) {
            pushCurrent(segments, current, pendingHeredocs);
            ConsumeResult consumed = consumePendingHeredocs(input, i, segments, pendingHeredocs);
            if (consumed.reason != null) {
              return Analysis.unsupported(consumed.reason, segments);
            }
            i = consumed.nextIndex - 1;
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
        if (isCommandSubstitutionStart(input, i) || ch == '`') {
          return Analysis.unsupported("command_substitution", segments);
        }
        if (ch == '"') {
          quote = null;
        }
        continue;
      }

      if (ch == '#' && isCommentStart(current)) {
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
      if (isCommandSubstitutionStart(input, i) || ch == '`') {
        return Analysis.unsupported("command_substitution", segments);
      }
      if ((ch == '<' || ch == '>') && next != null && next == '(') {
        return Analysis.unsupported("process_substitution", segments);
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
          return Analysis.unsupported("unmatched_closing_paren", segments);
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
          return Analysis.unsupported("unmatched_closing_brace", segments);
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
            ConsumeResult consumed = consumePendingHeredocs(input, i, segments, pendingHeredocs);
            if (consumed.reason != null) {
              return Analysis.unsupported(consumed.reason, segments);
            }
            i = consumed.nextIndex - 1;
            continue;
          }
          i += operatorLength - 1;
          continue;
        }
      }
      current.append(ch);
    }

    if (quote != null) {
      return Analysis.unsupported("unclosed_" + quote.name().toLowerCase() + "_quote", segments);
    }
    if (parenDepth > 0) {
      return Analysis.unsupported("unclosed_paren", segments);
    }
    if (braceDepth > 0) {
      return Analysis.unsupported("unclosed_brace", segments);
    }
    if (doubleBracketDepth > 0) {
      return Analysis.unsupported("unclosed_double_bracket", segments);
    }
    pushSegment(segments, current.toString());
    for (String segment : segments) {
      String reason = unsupportedSurfaceReason(segment);
      if (reason != null) {
        return Analysis.unsupported(reason, segments);
      }
    }
    return Analysis.supported(segments);
  }

  public List<String> buildCandidates(String segment) {
    String trimmed = segment.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    List<String> tokens = tokenize(trimmed);
    List<String> candidates = new ArrayList<>();
    candidates.add(trimmed);
    addPrefixCandidates(candidates, tokens);
    int commandStart = firstCommandIndex(tokens);
    if (commandStart > 0) {
      addPrefixCandidates(candidates, tokens.subList(commandStart, tokens.size()));
    }
    if (commandStart >= 0) {
      List<String> commandTokens = new ArrayList<>(tokens.subList(commandStart, tokens.size()));
      String executable = surfaceExecutableName(commandTokens.get(0));
      if (!executable.isEmpty() && !executable.equals(commandTokens.get(0))) {
        commandTokens.set(0, executable);
        addPrefixCandidates(candidates, commandTokens);
      }
    }
    return unique(candidates);
  }

  public List<String> tokenize(String segment) {
    String input = normalize(segment);
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

  private void pushCurrent(
      List<String> segments, StringBuilder current, List<PendingHeredoc> pendingHeredocs) {
    String trimmed = current.toString().trim();
    current.setLength(0);
    if (trimmed.isEmpty()) {
      return;
    }
    List<HeredocDelimiter> delimiters = extractHeredocDelimiters(trimmed);
    int segmentIndex = segments.size();
    segments.add(trimmed);
    if (!delimiters.isEmpty()) {
      pendingHeredocs.add(new PendingHeredoc(segmentIndex, delimiters));
    }
  }

  private ConsumeResult consumePendingHeredocs(
      String input, int newlineIndex, List<String> segments, List<PendingHeredoc> pendingHeredocs) {
    int currentNewlineIndex = newlineIndex;
    for (PendingHeredoc pending : pendingHeredocs) {
      ConsumeResult consumed = consumeHeredocBodies(input, currentNewlineIndex, pending.delimiters);
      if (consumed.reason != null) {
        return consumed;
      }
      segments.set(
          pending.segmentIndex, (segments.get(pending.segmentIndex) + consumed.text).trim());
      currentNewlineIndex = consumed.nextIndex - 1;
    }
    pendingHeredocs.clear();
    return new ConsumeResult(currentNewlineIndex + 1, null, null);
  }

  private ConsumeResult consumeHeredocBodies(
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
        String comparable = delimiter.stripLeadingTabs ? line.replaceFirst("^\\t+", "") : line;
        if (comparable.equals(delimiter.value)) {
          found = true;
          break;
        }
        if (delimiter.allowExpansion && hasExpandableCommandSubstitution(line)) {
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

  private List<HeredocDelimiter> extractHeredocDelimiters(String commandLine) {
    List<String> tokens = tokenize(commandLine);
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
      ParsedDelimiter parsed = parseHeredocDelimiter(raw);
      if (!parsed.value.isEmpty()) {
        delimiters.add(new HeredocDelimiter(parsed.value, stripTabs, parsed.allowExpansion));
      }
    }
    return delimiters;
  }

  private ParsedDelimiter parseHeredocDelimiter(String token) {
    StringBuilder value = new StringBuilder();
    Quote quote = null;
    boolean allowExpansion = true;
    for (int i = 0; i < token.length(); i++) {
      char ch = token.charAt(i);
      Character next = i + 1 < token.length() ? token.charAt(i + 1) : null;
      if (ch == '\\' && next != null && quote != Quote.SINGLE) {
        allowExpansion = false;
        if (quote == Quote.DOUBLE && "$`\"\\\n".indexOf(next) < 0) {
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

  private String unsupportedSurfaceReason(String segment) {
    List<String> tokens = tokenize(segment);
    int index = firstCommandIndex(tokens);
    if (index < 0) {
      return null;
    }
    String executableToken = tokens.get(index);
    if (executableToken.startsWith("[[") || executableToken.startsWith("((")) {
      return null;
    }
    if (hasExecutableRedirection(executableToken)) {
      return "command_redirection";
    }
    String executable = surfaceExecutableName(executableToken);
    if (isCompoundShellSyntax(tokens, index, executable)) {
      return "compound_shell_syntax";
    }
    if (hasExecutableExpansion(executableToken)) {
      return "dynamic_command_name";
    }
    if (COMMAND_LAUNCHERS.contains(executable)
        || executable.equals("eval")
        || executable.equals("source")
        || executable.equals(".")
        || executable.equals("alias")
        || DYNAMIC_SHELL_WRAPPERS.contains(executable)) {
      return "dynamic_shell_wrapper";
    }
    return null;
  }

  private boolean isCompoundShellSyntax(List<String> tokens, int commandIndex, String executable) {
    String token = tokens.get(commandIndex);
    if (COMPOUND_SHELL_KEYWORDS.contains(executable)
        || token.startsWith("(") && !token.startsWith("((")
        || token.startsWith("{")
        || token.matches("^[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\).*$")) {
      return true;
    }
    return token.matches("^[A-Za-z_][A-Za-z0-9_]*$")
        && commandIndex + 1 < tokens.size()
        && tokens.get(commandIndex + 1).startsWith("()");
  }

  private boolean hasExecutableExpansion(String token) {
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
        } else if (ch == '$') {
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
      } else if (ch == '$') {
        return true;
      }
    }
    return false;
  }

  private boolean hasExecutableRedirection(String token) {
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
        } else if (ch == '"') {
          quote = null;
        }
        continue;
      }
      if (ch == '\\' && next != null) {
        i++;
      } else if (ch == '\'') {
        quote = Quote.SINGLE;
      } else if (ch == '"') {
        quote = Quote.DOUBLE;
      } else if (ch == '<' || ch == '>') {
        return true;
      }
    }
    return false;
  }

  private String surfaceExecutableName(String token) {
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
        if (ch == '\\' && next != null && "$`\"\\\n".indexOf(next) >= 0) {
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

  private int firstCommandIndex(List<String> tokens) {
    for (int i = 0; i < tokens.size(); i++) {
      if (!ASSIGNMENT.matcher(tokens.get(i)).find()) {
        return i;
      }
    }
    return -1;
  }

  private void addPrefixCandidates(List<String> candidates, List<String> tokens) {
    for (int length = 1; length <= tokens.size(); length++) {
      String prefix = String.join(" ", tokens.subList(0, length));
      candidates.add(prefix);
      candidates.add(prefix + " *");
    }
  }

  private List<String> unique(List<String> values) {
    Set<String> seen = new HashSet<>();
    List<String> result = new ArrayList<>();
    for (String value : values) {
      if (!value.isEmpty() && seen.add(value)) {
        result.add(value);
      }
    }
    return List.copyOf(result);
  }

  private static boolean isCommandSubstitutionStart(String value, int index) {
    return index + 1 < value.length()
        && value.charAt(index) == '$'
        && value.charAt(index + 1) == '('
        && (index + 2 >= value.length() || value.charAt(index + 2) != '(');
  }

  private static boolean hasExpandableCommandSubstitution(String value) {
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '\\' && i + 1 < value.length()) {
        i++;
      } else if (isCommandSubstitutionStart(value, i) || ch == '`') {
        return true;
      }
    }
    return false;
  }

  private static boolean isCommentStart(StringBuilder current) {
    return current.length() == 0 || Character.isWhitespace(current.charAt(current.length() - 1));
  }

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

  private static void pushSegment(List<String> segments, String value) {
    String trimmed = value.trim();
    if (!trimmed.isEmpty()) {
      segments.add(trimmed);
    }
  }

  private static void pushToken(List<String> tokens, StringBuilder current) {
    if (current.length() > 0) {
      tokens.add(current.toString());
      current.setLength(0);
    }
  }

  private static String normalize(String command) {
    if (command == null) {
      throw new IllegalArgumentException("command must not be null");
    }
    return command.replace("\r\n", "\n").replace('\r', '\n').replace("\\\n", " ");
  }

  public record Analysis(boolean supported, String reason, List<String> segments) {
    public Analysis {
      segments = List.copyOf(segments);
    }

    public static Analysis supported(List<String> segments) {
      return new Analysis(true, null, segments);
    }

    public static Analysis unsupported(String reason, List<String> segments) {
      return new Analysis(false, reason, segments);
    }
  }

  private enum Quote {
    SINGLE,
    DOUBLE,
    BACKTICK
  }

  private record HeredocDelimiter(String value, boolean stripLeadingTabs, boolean allowExpansion) {}

  private record PendingHeredoc(int segmentIndex, List<HeredocDelimiter> delimiters) {}

  private record ParsedDelimiter(String value, boolean allowExpansion) {}

  private record ConsumeResult(int nextIndex, String reason, String text) {}
}

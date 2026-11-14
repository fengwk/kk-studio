package fun.fengwk.kkstudio.platform.harness.tool.permission;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 为权限匹配提取 Bash 顶层执行面，不启动 Shell。
 *
 * <p>分析器只识别确定的静态命令段，并处理引号、转义、顶层控制运算符和 heredoc 边界。命令替换、进程替换、复合语法、动态命令名、Shell 包装器及未闭合结构会返回 {@code
 * unsupported}，由权限层按完整原命令执行保守判定。
 *
 * <p>这不是完整 Bash 解析器，也不声称模拟运行时展开；它的输出仅用于构造权限规则候选。分段由 {@link BashSegmentScanner} 承担， token 化由 {@link
 * BashTokenizer} 承担，可执行位置判定由 {@link BashExecutableToken} 承担。
 */
public final class BashSurfaceAnalyzer {

  private static final Pattern ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(?:\\+)?=");

  /** 会自行解释后续文本的 Shell，包装器形态无法静态确定执行面。 */
  private static final Set<String> DYNAMIC_SHELL_WRAPPERS =
      Set.of("sh", "bash", "dash", "zsh", "ksh", "ksh93", "mksh", "fish");

  /** 把后续词当作命令继续执行的启动器。 */
  private static final Set<String> COMMAND_LAUNCHERS = Set.of("command", "env", "exec", "nohup");

  static final Set<String> COMPOUND_SHELL_KEYWORDS =
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

  /** 切分顶层命令段，并标记无法静态确定执行面的语法。 */
  public Analysis analyze(String command) {
    BashSegmentScanner.ScanResult scan =
        BashSegmentScanner.scan(BashCommandText.normalize(command));
    if (scan.reason() != null) {
      return Analysis.unsupported(scan.reason(), scan.segments());
    }
    for (String segment : scan.segments()) {
      String reason = unsupportedSurfaceReason(segment);
      if (reason != null) {
        return Analysis.unsupported(reason, scan.segments());
      }
    }
    return Analysis.supported(scan.segments());
  }

  /** 为静态命令段生成权限匹配候选，包括去除赋值前缀后的可执行文件名。 */
  public List<String> buildCandidates(String segment) {
    String trimmed = segment.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    List<String> tokens = BashTokenizer.tokenize(trimmed);
    List<String> candidates = new ArrayList<>();
    candidates.add(trimmed);
    addPrefixCandidates(candidates, tokens);
    int commandStart = firstCommandIndex(tokens);
    if (commandStart > 0) {
      addPrefixCandidates(candidates, tokens.subList(commandStart, tokens.size()));
    }
    if (commandStart >= 0) {
      List<String> commandTokens = new ArrayList<>(tokens.subList(commandStart, tokens.size()));
      String executable = BashExecutableToken.executableName(commandTokens.get(0));
      if (!executable.isEmpty() && !executable.equals(commandTokens.get(0))) {
        commandTokens.set(0, executable);
        addPrefixCandidates(candidates, commandTokens);
      }
    }
    return unique(candidates);
  }

  /** 返回阻止当前命令段参与静态权限匹配的原因；可分析时返回 {@code null}。 */
  private String unsupportedSurfaceReason(String segment) {
    List<String> tokens = BashTokenizer.tokenize(segment);
    int index = firstCommandIndex(tokens);
    if (index < 0) {
      return null;
    }
    String executableToken = tokens.get(index);
    if (executableToken.startsWith("[[") || executableToken.startsWith("((")) {
      return null;
    }
    if (BashExecutableToken.hasRedirection(executableToken)) {
      return "command_redirection";
    }
    String executable = BashExecutableToken.executableName(executableToken);
    if (BashExecutableToken.isCompoundShellSyntax(tokens, index, executable)) {
      return "compound_shell_syntax";
    }
    if (BashExecutableToken.hasExpansion(executableToken)) {
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

  private static int firstCommandIndex(List<String> tokens) {
    for (int i = 0; i < tokens.size(); i++) {
      if (!ASSIGNMENT.matcher(tokens.get(i)).find()) {
        return i;
      }
    }
    return -1;
  }

  private static void addPrefixCandidates(List<String> candidates, List<String> tokens) {
    for (int length = 1; length <= tokens.size(); length++) {
      String prefix = String.join(" ", tokens.subList(0, length));
      candidates.add(prefix);
      candidates.add(prefix + " *");
    }
  }

  private static List<String> unique(List<String> values) {
    Set<String> seen = new HashSet<>();
    List<String> result = new ArrayList<>();
    for (String value : values) {
      if (!value.isEmpty() && seen.add(value)) {
        result.add(value);
      }
    }
    return List.copyOf(result);
  }

  public record Analysis(boolean supported, String reason, List<String> segments) {
    public Analysis {
      segments = List.copyOf(segments);
    }

    static Analysis supported(List<String> segments) {
      return new Analysis(true, null, segments);
    }

    static Analysis unsupported(String reason, List<String> segments) {
      return new Analysis(false, reason, segments);
    }
  }
}

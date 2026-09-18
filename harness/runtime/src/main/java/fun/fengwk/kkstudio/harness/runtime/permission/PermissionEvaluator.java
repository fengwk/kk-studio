package fun.fengwk.kkstudio.harness.runtime.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 基于有序规则的 Tool permission evaluator。path target 只解析为 effective-workdir 相对 POSIX 路径，交给 JGit
 * gitignore 语义匹配；Bash/普通 command 候选仍使用简单 wildcard。这里只生成策略候选，不承担 T09 path/symlink 安全。
 */
public final class PermissionEvaluator {
  private static final int ARGUMENT_PREVIEW_LENGTH = 120;

  private final ObjectMapper objectMapper;
  private final BashSurfaceAnalyzer bashAnalyzer;
  private final PermissionPathMatcher pathMatcher;

  public PermissionEvaluator(ObjectMapper objectMapper, BashSurfaceAnalyzer bashAnalyzer) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.bashAnalyzer = Objects.requireNonNull(bashAnalyzer, "bashAnalyzer");
    this.pathMatcher = new PermissionPathMatcher();
  }

  /** 按调用方冻结的权限上下文评估 Tool 调用，返回 action 与 prompt preview。 */
  public Evaluation evaluate(PermissionEvaluationContext context) {
    JsonNode input = readArguments(context.argumentsJson());
    PermissionAction action;
    if (input.path("command").isTextual()) {
      action = evaluateBash(input.path("command").asText(), context.settings(), context.toolName());
    } else if (input.path("path").isTextual()
        && !input.path("path").asText().trim().isEmpty()
        && input.hasNonNull("workdir")) {
      PathTarget target =
          describePathTarget(input.path("path").asText(), workdirFromArguments(input));
      action = evaluatePathRules(target, context.settings(), context.toolName());
    } else {
      action =
          evaluateWildcardRules(describeCandidates(input), context.settings(), context.toolName());
    }
    return new Evaluation(action, promptPreview(context, input));
  }

  public PermissionPromptPreview preview(PermissionEvaluationContext context) {
    return promptPreview(context, readArguments(context.argumentsJson()));
  }

  /**
   * 把工具 {@code path} 参数词法解析为相对目标。只保留到该次调用 explicit workdir 的规范相对 POSIX 路径；绝对 target 直接词法
   * relativize，相对 target 基于该 workdir 解析。全过程不读取 Backend cwd/HOME，Environment root 与 Backend 文件系统都不参与
   * pattern 坐标。
   *
   * <p>workdir 只能来自本次调用的 {@code arguments.workdir}：没有该字段的调用（例如 {@code load_skill}）不会获得隐藏默认目录。
   */
  PathTarget describePathTarget(String rawPath, String workdir) {
    boolean directory = rawPath.endsWith("/") || rawPath.endsWith("\\");
    String unifiedWorkdir = unifySeparators(workdir);
    boolean windows = isWindowsAbsolute(unifiedWorkdir) || unifiedWorkdir.startsWith("//");
    String normalizedWorkdir = LexicalTargetPath.normalizeAbsolute(unifiedWorkdir);
    String unified = rawPath.indexOf('\\') < 0 ? rawPath : rawPath.replace('\\', '/');
    if (!windows && unified.startsWith("//")) {
      unified = collapseUnixRoot(unified);
    }
    String absolute =
        startsWithRoot(unified, windows)
            ? LexicalTargetPath.normalizeAbsolute(unified)
            : LexicalTargetPath.normalizeAbsolute(normalizedWorkdir + "/" + unified);
    return new PathTarget(LexicalTargetPath.relativize(normalizedWorkdir, absolute), directory);
  }

  /** 按 explicit workdir 的路径族判断 target 是否为绝对路径，避免用 Backend OS 解释远端文本。 */
  private static boolean startsWithRoot(String unified, boolean windows) {
    if (!windows) {
      return unified.startsWith("/");
    }
    return unified.startsWith("//") || isWindowsAbsolute(unified);
  }

  private static boolean isWindowsAbsolute(String unified) {
    return unified.length() >= 3
        && Character.isLetter(unified.charAt(0))
        && unified.charAt(1) == ':'
        && unified.charAt(2) == '/';
  }

  private static String unifySeparators(String value) {
    return value.indexOf('\\') < 0 ? value : value.replace('\\', '/');
  }

  /** Unix 的多个前导 slash 属于同一 root；折叠后避免被无 OS 上下文的 UNC 解析分支误判。 */
  private static String collapseUnixRoot(String value) {
    int index = 1;
    while (index < value.length() && value.charAt(index) == '/') {
      index++;
    }
    return "/" + value.substring(index);
  }

  List<String> describeCandidates(JsonNode input) {
    if (input.path("command").isTextual()) {
      String command = input.path("command").asText().trim();
      return List.of(command.isEmpty() ? "<missing-command>" : command);
    }
    return List.of("*");
  }

  private PermissionAction evaluatePathRules(
      PathTarget target, ToolSettings settings, String toolName) {
    PermissionAction action = PermissionAction.ALLOW;
    List<List<PermissionRule>> rulesets =
        List.of(settings.globalRules(), settings.rulesFor(toolName));
    for (List<PermissionRule> rules : rulesets) {
      for (PermissionRule rule : rules) {
        if (pathMatcher.matches(rule.pattern(), target)) {
          action = rule.action();
        }
      }
    }
    return action;
  }

  private PermissionAction evaluateBash(String command, ToolSettings settings, String toolName) {
    BashSurfaceAnalyzer.Analysis analysis = bashAnalyzer.analyze(command);
    if (!analysis.supported()) {
      PermissionAction staticAction = evaluateWildcardRules(List.of(command), settings, toolName);
      return staticAction == PermissionAction.DENY ? PermissionAction.DENY : PermissionAction.ASK;
    }
    if (analysis.segments().isEmpty()) {
      return evaluateWildcardRules(List.of(command), settings, toolName);
    }
    PermissionAction action = PermissionAction.ALLOW;
    for (String segment : analysis.segments()) {
      PermissionAction next =
          evaluateWildcardRules(bashAnalyzer.buildCandidates(segment), settings, toolName);
      if (next == PermissionAction.DENY) {
        return PermissionAction.DENY;
      }
      if (next == PermissionAction.ASK) {
        action = PermissionAction.ASK;
      }
    }
    return action;
  }

  private PermissionAction evaluateWildcardRules(
      List<String> candidates, ToolSettings settings, String toolName) {
    PermissionAction action = PermissionAction.ALLOW;
    List<List<PermissionRule>> rulesets =
        List.of(settings.globalRules(), settings.rulesFor(toolName));
    for (List<PermissionRule> rules : rulesets) {
      for (PermissionRule rule : rules) {
        if (candidates.stream().anyMatch(candidate -> wildcardMatches(rule.pattern(), candidate))) {
          action = rule.action();
        }
      }
    }
    return action;
  }

  /** prompt preview 只展示该调用真实携带的 workdir：没有该字段的工具（{@code load_skill}、MCP 等）不显示任何虚构默认目录。 */
  private PermissionPromptPreview promptPreview(
      PermissionEvaluationContext context, JsonNode input) {
    JsonNode workdirNode = input.get("workdir");
    String workdir;
    if (workdirNode == null || workdirNode.isNull()) {
      workdir = null;
    } else if (!workdirNode.isTextual() || workdirNode.asText().trim().isEmpty()) {
      workdir = "<invalid-workdir>";
    } else {
      workdir = singleLine(workdirNode.asText());
    }
    return new PermissionPromptPreview(
        context.toolName(), workdir, truncate(singleLine(writeArguments(input))));
  }

  private String writeArguments(JsonNode input) {
    try {
      return objectMapper.writeValueAsString(input);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(
          "cannot encode tool arguments for permission preview", error);
    }
  }

  /** 只读取本次 arguments 的 workdir；空值与非绝对路径是调用方错误，不存在默认值或展示前缀展开。 */
  private static String workdirFromArguments(JsonNode input) {
    JsonNode workdirNode = input.get("workdir");
    if (!workdirNode.isTextual()) {
      throw new IllegalArgumentException("workdir must be a non-blank string");
    }
    String rawWorkdir = workdirNode.asText();
    if (rawWorkdir.isBlank()) {
      throw new IllegalArgumentException("workdir must be a non-blank string");
    }
    if (!rawWorkdir.equals(rawWorkdir.strip())) {
      throw new IllegalArgumentException("workdir must not have surrounding whitespace");
    }
    return LexicalTargetPath.normalizeAbsolute(rawWorkdir);
  }

  private JsonNode readArguments(String argumentsJson) {
    try {
      JsonNode node = objectMapper.readTree(argumentsJson);
      if (!node.isObject()) {
        throw new IllegalArgumentException("tool arguments must be a JSON object");
      }
      return node;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("tool arguments must be valid JSON", error);
    }
  }

  private boolean wildcardMatches(String pattern, String candidate) {
    String normalizedPattern = display(pattern.trim());
    String normalizedCandidate = display(candidate.trim());
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < normalizedPattern.length(); i++) {
      char ch = normalizedPattern.charAt(i);
      if (ch == '*') {
        regex.append(".*");
      } else if (ch == '?') {
        regex.append('.');
      } else {
        regex.append(Pattern.quote(String.valueOf(ch)));
      }
    }
    regex.append('$');
    return Pattern.compile(regex.toString()).matcher(normalizedCandidate).matches();
  }

  private static String truncate(String value) {
    if (value.length() <= ARGUMENT_PREVIEW_LENGTH) {
      return value.isEmpty() ? "{}" : value;
    }
    return value.substring(0, ARGUMENT_PREVIEW_LENGTH - 3) + "...";
  }

  private static String singleLine(String value) {
    return value.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").trim();
  }

  private static String display(String value) {
    return value.replace('\\', '/');
  }

  /** 单次调用 effective workdir 相对的目标：POSIX 路径与 raw path 末尾分隔符表达的 directory hint。 */
  record PathTarget(String relativePosixPath, boolean directory) {}

  public record Evaluation(PermissionAction action, PermissionPromptPreview promptPreview) {
    public Evaluation {
      action = Objects.requireNonNull(action, "action");
      promptPreview = Objects.requireNonNull(promptPreview, "promptPreview");
    }
  }
}

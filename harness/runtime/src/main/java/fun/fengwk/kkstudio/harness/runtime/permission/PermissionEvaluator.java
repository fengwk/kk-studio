package fun.fengwk.kkstudio.harness.runtime.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 基于有序规则的 Tool permission evaluator。文件工具的 {@code path} 是绝对路径，path target 按 filesystem-root 坐标（Unix
 * 去掉 root 前缀，Windows 保留 root-qualified）交给 JGit gitignore 语义匹配；Bash/普通 command 候选仍使用简单
 * wildcard。这里只生成策略候选，不承担 T09 path/symlink 安全。
 *
 * <p>非绝对 {@code path}（例如 {@code kkstudio:} 资源 URI、drive-relative 或普通相对文本）不进入 path 规则，而回落 wildcard
 * 规则；路径本身是否为 合法绝对路径由目标 Daemon 判定。
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
    } else if (isAbsolutePathTarget(input.path("path"))) {
      PathTarget target = describePathTarget(input.path("path").asText());
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

  /** 只有绝对 {@code path} 才是文件系统路径目标；URI scheme 与相对文本一律不作为 path 坐标。 */
  private static boolean isAbsolutePathTarget(JsonNode pathNode) {
    return pathNode.isTextual()
        && !pathNode.asText().trim().isEmpty()
        && LexicalTargetPath.isAbsolute(pathNode.asText());
  }

  /**
   * 把绝对 {@code path} 词法解析为 filesystem-root 匹配坐标：Unix 去掉 {@code /} 前缀，Windows drive/UNC 保留
   * root-qualified；全程不读取 Backend cwd/HOME，Environment root 与 Backend 文件系统都不参与 pattern 坐标。
   */
  PathTarget describePathTarget(String rawPath) {
    boolean directory = rawPath.endsWith("/") || rawPath.endsWith("\\");
    return new PathTarget(LexicalTargetPath.permissionCoordinate(rawPath), directory);
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

  /** prompt preview 只展示该调用真实携带的 workdir：没有该字段的工具（文件工具、{@code task}、MCP 等）不显示任何虚构默认目录。 */
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

  /** 单次调用绝对 {@code path} 的 filesystem-root 匹配坐标与 raw path 末尾分隔符表达的 directory hint。 */
  record PathTarget(String posixPath, boolean directory) {}

  public record Evaluation(PermissionAction action, PermissionPromptPreview promptPreview) {
    public Evaluation {
      action = Objects.requireNonNull(action, "action");
      promptPreview = Objects.requireNonNull(promptPreview, "promptPreview");
    }
  }
}

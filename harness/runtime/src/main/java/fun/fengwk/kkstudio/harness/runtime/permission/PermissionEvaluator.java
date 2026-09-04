package fun.fengwk.kkstudio.harness.runtime.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.nio.file.Path;
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
      action = evaluateBash(input.path("command").asText(), context.settings(), context.toolId());
    } else if (input.path("path").isTextual() && !input.path("path").asText().trim().isEmpty()) {
      Path targetWorkdir = resolveWorkdir(input, context.workdir());
      PathTarget target = describePathTarget(input.path("path").asText(), targetWorkdir);
      action = evaluatePathRules(target, context.settings(), context.toolId());
    } else {
      action =
          evaluateWildcardRules(describeCandidates(input), context.settings(), context.toolId());
    }
    return new Evaluation(action, promptPreview(context, input));
  }

  public PermissionPromptPreview preview(PermissionEvaluationContext context) {
    return promptPreview(context, readArguments(context.argumentsJson()));
  }

  /**
   * 把工具 {@code path} 参数解析为相对目标。只保留到该次调用 effective workdir 的规范相对 POSIX 路径；raw、
   * environment-root-relative 与 absolute 候选不再产生，Environment root 不参与 pattern 坐标。
   */
  PathTarget describePathTarget(String rawPath, Path workdir) {
    String stripped = stripAtPrefix(rawPath);
    boolean directory = stripped.endsWith("/") || stripped.endsWith("\\");
    Path resolved = Path.of(display(expandHome(stripped)));
    Path absolute =
        resolved.isAbsolute() ? resolved.normalize() : workdir.resolve(resolved).normalize();
    String relative = relativeDisplay(workdir, absolute);
    return new PathTarget(relative, directory);
  }

  List<String> describeCandidates(JsonNode input) {
    if (input.path("command").isTextual()) {
      String command = input.path("command").asText().trim();
      return List.of(command.isEmpty() ? "<missing-command>" : command);
    }
    return List.of("*");
  }

  private PermissionAction evaluatePathRules(
      PathTarget target, ToolSettings settings, AgentToolId toolId) {
    PermissionAction action = PermissionAction.ALLOW;
    List<List<PermissionRule>> rulesets =
        List.of(settings.globalRules(), settings.rulesFor(toolId));
    for (List<PermissionRule> rules : rulesets) {
      for (PermissionRule rule : rules) {
        if (pathMatcher.matches(rule.pattern(), target)) {
          action = rule.action();
        }
      }
    }
    return action;
  }

  private PermissionAction evaluateBash(String command, ToolSettings settings, AgentToolId toolId) {
    BashSurfaceAnalyzer.Analysis analysis = bashAnalyzer.analyze(command);
    if (!analysis.supported()) {
      PermissionAction staticAction = evaluateWildcardRules(List.of(command), settings, toolId);
      return staticAction == PermissionAction.DENY ? PermissionAction.DENY : PermissionAction.ASK;
    }
    if (analysis.segments().isEmpty()) {
      return evaluateWildcardRules(List.of(command), settings, toolId);
    }
    PermissionAction action = PermissionAction.ALLOW;
    for (String segment : analysis.segments()) {
      PermissionAction next =
          evaluateWildcardRules(bashAnalyzer.buildCandidates(segment), settings, toolId);
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
      List<String> candidates, ToolSettings settings, AgentToolId toolId) {
    PermissionAction action = PermissionAction.ALLOW;
    List<List<PermissionRule>> rulesets =
        List.of(settings.globalRules(), settings.rulesFor(toolId));
    for (List<PermissionRule> rules : rulesets) {
      for (PermissionRule rule : rules) {
        if (candidates.stream().anyMatch(candidate -> wildcardMatches(rule.pattern(), candidate))) {
          action = rule.action();
        }
      }
    }
    return action;
  }

  private PermissionPromptPreview promptPreview(
      PermissionEvaluationContext context, JsonNode input) {
    JsonNode workdirNode = input.get("workdir");
    String workdir;
    if (workdirNode == null || workdirNode.isNull()) {
      workdir = display(context.workdir()) + " (default)";
    } else if (!workdirNode.isTextual() || stripAtPrefix(workdirNode.asText()).trim().isEmpty()) {
      workdir = "<invalid-workdir>";
    } else {
      workdir = singleLine(stripAtPrefix(workdirNode.asText()));
    }
    return new PermissionPromptPreview(
        context.toolId().value(), workdir, truncate(singleLine(writeArguments(input))));
  }

  private String writeArguments(JsonNode input) {
    try {
      return objectMapper.writeValueAsString(input);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(
          "cannot encode tool arguments for permission preview", error);
    }
  }

  private Path resolveWorkdir(JsonNode input, Path defaultWorkdir) {
    JsonNode workdirNode = input.get("workdir");
    if (workdirNode == null || workdirNode.isNull()) {
      return defaultWorkdir;
    }
    if (!workdirNode.isTextual()) {
      throw new IllegalArgumentException("workdir must be a non-blank string when provided");
    }
    String rawWorkdir = stripAtPrefix(workdirNode.asText().trim());
    if (rawWorkdir.isBlank()) {
      throw new IllegalArgumentException("workdir must be a non-blank string when provided");
    }
    Path configured = Path.of(display(expandHome(rawWorkdir)));
    return configured.isAbsolute()
        ? configured.toAbsolutePath().normalize()
        : defaultWorkdir.resolve(configured).toAbsolutePath().normalize();
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
    String normalizedPattern = display(expandHome(pattern.trim()));
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

  private static String relativeDisplay(Path base, Path target) {
    try {
      String relative = display(base.relativize(target));
      return relative.isEmpty() ? "." : relative;
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("path is not reachable from the effective workdir", error);
    }
  }

  private static String expandHome(String value) {
    String home = System.getProperty("user.home");
    if (value.equals("~") || value.equals("$HOME") || value.equals("${HOME}")) {
      return home;
    }
    if (value.startsWith("~/") || value.startsWith("~\\")) {
      return home + "/" + value.substring(2);
    }
    if (value.startsWith("$HOME/") || value.startsWith("$HOME\\")) {
      return home + "/" + value.substring(6);
    }
    if (value.startsWith("${HOME}/") || value.startsWith("${HOME}\\")) {
      return home + "/" + value.substring(8);
    }
    return value;
  }

  private static String stripAtPrefix(String value) {
    return value.startsWith("@") ? value.substring(1) : value;
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

  private static String display(Path value) {
    return display(value.toString());
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

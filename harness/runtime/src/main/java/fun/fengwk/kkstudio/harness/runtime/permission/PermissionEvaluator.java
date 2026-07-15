package fun.fengwk.kkstudio.harness.runtime.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.harness.runtime.tool.BeforeToolCallContext;
import fun.fengwk.kkstudio.harness.runtime.tool.BeforeToolCallResult;
import fun.fengwk.kkstudio.harness.runtime.tool.PermissionBoundaryInterceptor;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** PiBase 等价的 ordered Tool permission evaluator。路径仅构造候选，不承担 T09 path/symlink 安全。 */
public final class PermissionEvaluator implements PermissionBoundaryInterceptor {
  private static final int ARGUMENT_PREVIEW_LENGTH = 120;

  private final ObjectMapper objectMapper;
  private final BashSurfaceAnalyzer bashAnalyzer;

  public PermissionEvaluator(ObjectMapper objectMapper, BashSurfaceAnalyzer bashAnalyzer) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.bashAnalyzer = Objects.requireNonNull(bashAnalyzer, "bashAnalyzer");
  }

  @Override
  public BeforeToolCallResult intercept(BeforeToolCallContext context) {
    Evaluation evaluation =
        evaluate(
            new PermissionEvaluationContext(
                context.binding().descriptor().name(),
                context.call().argumentsJson(),
                context.workdir(),
                context.environmentRoot(),
                context.settings()));
    PermissionAction action = context.yoloEnabled() ? PermissionAction.ALLOW : evaluation.action();
    return new BeforeToolCallResult(
        context.binding(), context.call().argumentsJson(), action, evaluation.promptPreview());
  }

  public Evaluation evaluate(PermissionEvaluationContext context) {
    JsonNode input = readArguments(context.argumentsJson());
    PermissionAction action;
    if (context.toolName().equals("bash") && input.path("command").isTextual()) {
      action = evaluateBash(input.path("command").asText(), context.settings(), context.toolName());
    } else {
      action =
          evaluateRules(describeCandidates(input, context), context.settings(), context.toolName());
    }
    return new Evaluation(action, promptPreview(context, input));
  }

  public PermissionPromptPreview preview(PermissionEvaluationContext context) {
    return promptPreview(context, readArguments(context.argumentsJson()));
  }

  List<String> describeCandidates(JsonNode input, PermissionEvaluationContext context) {
    if (input.path("path").isTextual() && !input.path("path").asText().trim().isEmpty()) {
      Path targetWorkdir = resolveWorkdir(input, context.workdir());
      return buildPathCandidates(
          input.path("path").asText(), targetWorkdir, context.environmentRoot());
    }
    if (input.path("command").isTextual()) {
      String command = input.path("command").asText().trim();
      return List.of(command.isEmpty() ? "<missing-command>" : command);
    }
    return List.of("*");
  }

  List<String> buildPathCandidates(String rawPath, Path workdir, Path environmentRoot) {
    String stripped = rawPath.startsWith("@") ? rawPath.substring(1) : rawPath;
    Path raw = Path.of(expandHome(stripped));
    Path absolute = raw.isAbsolute() ? raw.normalize() : workdir.resolve(raw).normalize();
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    addCandidate(candidates, stripped);
    addCandidate(candidates, relativeDisplay(workdir, absolute));
    if (absolute.startsWith(environmentRoot)) {
      addCandidate(candidates, relativeDisplay(environmentRoot, absolute));
    }
    addCandidate(candidates, display(absolute));
    return List.copyOf(candidates);
  }

  private PermissionAction evaluateBash(String command, ToolSettings settings, String toolName) {
    BashSurfaceAnalyzer.Analysis analysis = bashAnalyzer.analyze(command);
    if (!analysis.supported()) {
      PermissionAction staticAction = evaluateRules(List.of(command), settings, toolName);
      return staticAction == PermissionAction.DENY ? PermissionAction.DENY : PermissionAction.ASK;
    }
    if (analysis.segments().isEmpty()) {
      return evaluateRules(List.of(command), settings, toolName);
    }
    PermissionAction action = PermissionAction.ALLOW;
    for (String segment : analysis.segments()) {
      PermissionAction next =
          evaluateRules(bashAnalyzer.buildCandidates(segment), settings, toolName);
      if (next == PermissionAction.DENY) {
        return PermissionAction.DENY;
      }
      if (next == PermissionAction.ASK) {
        action = PermissionAction.ASK;
      }
    }
    return action;
  }

  private PermissionAction evaluateRules(
      List<String> candidates, ToolSettings settings, String toolName) {
    PermissionAction action = PermissionAction.ALLOW;
    List<List<PermissionRule>> rulesets =
        List.of(settings.rulesFor("*"), settings.rulesFor(toolName));
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
    Path configured = Path.of(expandHome(rawWorkdir));
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

  private static void addCandidate(LinkedHashSet<String> candidates, String value) {
    if (value == null) {
      return;
    }
    String normalized = display(value);
    if (!normalized.isEmpty()) {
      candidates.add(normalized);
    }
  }

  private static String relativeDisplay(Path base, Path target) {
    try {
      String relative = display(base.relativize(target));
      return relative.isEmpty() ? "." : relative;
    } catch (IllegalArgumentException error) {
      return null;
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

  public record Evaluation(PermissionAction action, PermissionPromptPreview promptPreview) {
    public Evaluation {
      action = Objects.requireNonNull(action, "action");
      promptPreview = Objects.requireNonNull(promptPreview, "promptPreview");
    }
  }
}

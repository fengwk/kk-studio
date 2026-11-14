package fun.fengwk.kkstudio.platform.harness.tool.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class PermissionContractsTest {
  private static final String BASH = "bash";
  private static final String WRITE = "write";

  private final PermissionEvaluator evaluator = new PermissionEvaluator(new BashSurfaceAnalyzer());

  @Test
  void acceptsGlobalAndCanonicalIdRulesAndRejectsInvalidKeys() {
    List<PermissionRule> globalRules = List.of(new PermissionRule("*", PermissionAction.ASK));
    List<PermissionRule> toolRules = List.of(new PermissionRule("*", PermissionAction.DENY));
    Map<String, List<PermissionRule>> permission = new LinkedHashMap<>();
    permission.put(PermissionKeyValidator.GLOBAL_KEY, globalRules);
    permission.put(BASH, toolRules);

    ToolSettings settings = new ToolSettings(permission);

    assertEquals(globalRules, settings.globalRules());
    assertEquals(toolRules, settings.rulesFor(BASH));

    for (String invalid : new String[] {null, "", " ", " bash", "1bash", "bash ", "bash.x"}) {
      Map<String, List<PermissionRule>> invalidPermission = new LinkedHashMap<>();
      invalidPermission.put(invalid, toolRules);
      assertThrows(
          IllegalArgumentException.class,
          () -> new ToolSettings(invalidPermission),
          String.valueOf(invalid));
    }
  }

  /** wildcard `?`、非绝对 path 回落与空 command 使用确定候选。 */
  @Test
  void evaluatesWildcardAndPathFallbackTargets() {
    Map<String, List<PermissionRule>> rules = new LinkedHashMap<>();
    rules.put(
        WRITE,
        List.of(
            new PermissionRule("*", PermissionAction.ASK),
            new PermissionRule("file?.txt", PermissionAction.ALLOW),
            new PermissionRule("secret", PermissionAction.DENY)));
    ToolSettings settings = new ToolSettings(rules);

    assertEquals(PermissionAction.ALLOW, evaluate(WRITE, "{\"path\":\"file1.txt\"}", settings));
    // 绝对 path 按 filesystem-root 坐标命中 basename 规则。
    assertEquals(
        PermissionAction.DENY, evaluate(WRITE, "{\"path\":\"/outside/secret\"}", settings));
    // 相对 path 不是路径坐标，回落 wildcard 候选 `*`。
    assertEquals(
        PermissionAction.ASK,
        evaluateRaw(WRITE, "{\"workdir\":\"/outside\",\"path\":\"secret\"}", settings));
    assertEquals(
        PermissionAction.ASK,
        evaluate(
            BASH,
            "{\"command\":\"\"}",
            new ToolSettings(
                Map.of(BASH, List.of(new PermissionRule("*", PermissionAction.ASK))))));
  }

  /** malformed arguments、null/空白 tool name 与 action 解析必须在 preparation 前失败。 */
  @Test
  void rejectsInvalidPermissionContracts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> evaluator.evaluate(new PermissionEvaluationContext(WRITE, "[]", empty())));
    assertThrows(
        NullPointerException.class, () -> new PermissionEvaluationContext(null, "{}", empty()));
    // 空白 tool 身份无法匹配任何 tool-specific 规则，必须在边界 fail-fast。
    assertThrows(
        IllegalArgumentException.class, () -> new PermissionEvaluationContext("", "{}", empty()));
    assertThrows(
        IllegalArgumentException.class, () -> new PermissionEvaluationContext(" ", "{}", empty()));
    // workdir 不参与 path 解析：相对 path 回落 wildcard。
    PermissionEvaluationContext relativePath =
        new PermissionEvaluationContext(WRITE, "{\"path\":\"x\",\"workdir\":\"@\"}", empty());
    assertEquals(PermissionAction.ALLOW, evaluator.evaluate(relativePath));
    assertEquals(PermissionAction.ALLOW, PermissionAction.fromValue("allow"));
    assertEquals(PermissionAction.ASK, PermissionAction.fromValue("ask"));
    assertEquals(PermissionAction.DENY, PermissionAction.fromValue("deny"));
    assertThrows(IllegalArgumentException.class, () -> PermissionAction.fromValue("invalid"));
    assertThrows(IllegalArgumentException.class, () -> PermissionAction.fromValue("ALLOW"));
    assertThrows(IllegalArgumentException.class, () -> PermissionAction.fromValue(" allow "));
    assertThrows(IllegalArgumentException.class, () -> PermissionAction.fromValue(null));
    assertThrows(
        IllegalArgumentException.class, () -> new PermissionRule(" ", PermissionAction.ALLOW));
  }

  /** permission action 已表达 allow/ask/deny：path pattern 的 negation、comment 与无效形态必须 fail-fast。 */
  @Test
  void rejectsNegatedCommentAndInvalidPathPatterns() {
    for (String invalid : List.of("!logs/", "#comment", "[unclosed-class", "\\", "logs/\\")) {
      ToolSettings settings =
          settings(
              WRITE,
              new PermissionRule("*", PermissionAction.ALLOW),
              new PermissionRule(invalid, PermissionAction.DENY));
      assertThrows(
          IllegalArgumentException.class,
          () -> evaluate(WRITE, "{\"path\":\"logs/a.log\"}", settings),
          invalid);
    }
  }

  /** 转义后的 literal {@code \!}/{@code \#} pattern 交由 JGit 处理并可正常匹配。 */
  @Test
  void supportsEscapedLiteralPathPatterns() {
    assertEquals(
        PermissionAction.DENY,
        evaluate(
            WRITE,
            "{\"path\":\"!literal.txt\"}",
            settings(WRITE, new PermissionRule("\\!literal.txt", PermissionAction.DENY))));
    assertEquals(
        PermissionAction.DENY,
        evaluate(
            WRITE,
            "{\"path\":\"#literal.txt\"}",
            settings(WRITE, new PermissionRule("\\#literal.txt", PermissionAction.DENY))));
  }

  /** 严格 JSON 边界：重复字段、尾随 token、空白文档与非 object 文档一律 fail-closed。 */
  @Test
  void rejectsDuplicateTrailingTokensAndNonObjectArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> evaluator.evaluate(new PermissionEvaluationContext(WRITE, "{} {}", empty())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evaluator.evaluate(
                new PermissionEvaluationContext(
                    WRITE, "{\"path\":\"/a\",\"path\":\"/b\"}", empty())));
    // 空白 arguments 在上下文边界 fail-closed。
    assertThrows(
        IllegalArgumentException.class,
        () -> new PermissionEvaluationContext(WRITE, "   ", empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> evaluator.evaluate(new PermissionEvaluationContext(WRITE, "null", empty())));
  }

  private static ToolSettings empty() {
    return new ToolSettings(Map.of());
  }

  private static ToolSettings settings(String toolName, PermissionRule... rules) {
    Map<String, List<PermissionRule>> permission = new LinkedHashMap<>();
    permission.put(toolName, List.of(rules));
    return new ToolSettings(permission);
  }

  /** path 规则夹具：把相对 fixture 根到 filesystem root（坐标即 fixture 文本）；其余形态原样评估。 */
  private PermissionAction evaluate(String tool, String arguments, ToolSettings settings) {
    if (arguments.startsWith("{\"path\":")) {
      return evaluator.evaluate(
          new PermissionEvaluationContext(
              tool, "{\"path\":\"/" + arguments.substring("{\"path\":\"".length()), settings));
    }
    return evaluator.evaluate(new PermissionEvaluationContext(tool, arguments, settings));
  }

  private PermissionAction evaluateRaw(String tool, String arguments, ToolSettings settings) {
    return evaluator.evaluate(new PermissionEvaluationContext(tool, arguments, settings));
  }
}

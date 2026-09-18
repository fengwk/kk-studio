package fun.fengwk.kkstudio.harness.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class PermissionContractsTest {
  private static final String BASH = "bash";
  private static final String WRITE = "write";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ToolSettingsCodec codec = new ToolSettingsCodec(objectMapper);
  private final PermissionEvaluator evaluator =
      new PermissionEvaluator(objectMapper, new BashSurfaceAnalyzer());

  /** codec 接受 canonical list、null permission 和未知字段，并拒绝所有非规范类型。 */
  @Test
  void validatesCanonicalToolSettingsShapes() {
    String canonical =
        codec.canonicalize(
            "{\"other\":1,\"permission\":{\"bash\":[{\"pattern\":\"git"
                + " ?\",\"action\":\"allow\"}]}}");
    assertTrue(canonical.contains("\"other\":1"));
    assertEquals(PermissionAction.ALLOW, codec.decode(canonical).rulesFor(BASH).get(0).action());
    assertFalse(codec.decode("{\"permission\":null}").defaultYolo());

    for (String invalid :
        List.of(
            "[]",
            "not-json",
            "{\"defaultYolo\":1}",
            "{\"permission\":\"invalid\"}",
            "{\"permission\":\"allow\"}",
            "{\"permission\":\"ask\"}",
            "{\"permission\":\"deny\"}",
            "{\"permission\":[]}",
            "{\"permission\":{\"bash\":1}}",
            "{\"permission\":{\"bash\":\"allow\"}}",
            "{\"permission\":{\"bash\":{\"*\":\"allow\"}}}",
            "{\"permission\":{\"bash\":{\"*\":1}}}",
            "{\"permission\":{\"bash\":[{}]}}",
            "{\"permission\":{\"bash\":[{\"pattern\":\"*\"}]}}",
            "{\"permission\":{\"bash\":[{\"action\":\"allow\"}]}}",
            "{\"permission\":{\"bash\":[{\"pattern\":123,\"action\":\"allow\"}]}}",
            "{\"permission\":{\"bash\":[{\"pattern\":\"*\",\"action\":true}]}}",
            "{\"permission\":{\"bash\":[\"allow\"]}}",
            "{\"permission\":{\"bash\":[{\"pattern\":\"*\",\"action\":\"allow\",\"extra\":1}]}}",
            "{\"permission\":{\"bash\":[{\"pattern\":\"*\",\"action\":\"ALLOW\"}]}}",
            "{\"permission\":{\"bash\":[{\"pattern\":\"*\",\"action\":\"invalid\"}]}}",
            "{\"permission\":{\"1bash\":[]}}",
            "{\"permission\":{\" bash\":[]}}",
            "{\"permission\":{\"bash \":[]}}")) {
      assertThrows(IllegalArgumentException.class, () -> codec.canonicalize(invalid), invalid);
    }

    IllegalArgumentException nonCanonicalShortcut =
        assertThrows(
            IllegalArgumentException.class, () -> codec.canonicalize("{\"permission\":\"allow\"}"));
    assertEquals("permission must be a JSON object", nonCanonicalShortcut.getMessage());

    IllegalArgumentException perToolShortcut =
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.canonicalize("{\"permission\":{\"bash\":\"allow\"}}"));
    assertEquals("permission rules must be an array", perToolShortcut.getMessage());

    IllegalArgumentException extraField =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                codec.canonicalize(
                    "{\"permission\":{\"bash\":[{\"pattern\":\"*\",\"action\":\"allow\",\"extra\":1}]}}"));
    assertEquals(
        "permission rule must be an object with exact string keys 'pattern' and 'action'",
        extraField.getMessage());

    IllegalArgumentException invalidKey =
        assertThrows(
            IllegalArgumentException.class, () -> codec.decode("{\"permission\":{\" bash\":1}}"));
    assertEquals(
        "permission key must be '*' or a valid model-visible tool name", invalidKey.getMessage());
  }

  @Test
  void acceptsGlobalAndCanonicalIdRulesAndRejectsInvalidKeys() {
    List<PermissionRule> globalRules = List.of(new PermissionRule("*", PermissionAction.ASK));
    List<PermissionRule> toolRules = List.of(new PermissionRule("*", PermissionAction.DENY));
    Map<String, List<PermissionRule>> permission = new LinkedHashMap<>();
    permission.put(PermissionKeyValidator.GLOBAL_KEY, globalRules);
    permission.put(BASH, toolRules);

    ToolSettings settings = new ToolSettings(permission, false);

    assertEquals(globalRules, settings.globalRules());
    assertEquals(toolRules, settings.rulesFor(BASH));

    for (String invalid : new String[] {null, "", " ", " bash", "1bash", "bash ", "bash.x"}) {
      Map<String, List<PermissionRule>> invalidPermission = new LinkedHashMap<>();
      invalidPermission.put(invalid, toolRules);
      assertThrows(
          IllegalArgumentException.class,
          () -> new ToolSettings(invalidPermission, false),
          String.valueOf(invalid));
    }
  }

  /** wildcard `?`、绝对 workdir 与空 command 使用确定候选。 */
  @Test
  void evaluatesWildcardWorkdirRelativeAndEmptyTargets() {
    Map<String, List<PermissionRule>> rules = new LinkedHashMap<>();
    rules.put(
        WRITE,
        List.of(
            new PermissionRule("*", PermissionAction.ASK),
            new PermissionRule("file?.txt", PermissionAction.ALLOW),
            new PermissionRule("secret", PermissionAction.DENY)));
    ToolSettings settings = new ToolSettings(rules, false);

    assertEquals(
        PermissionAction.ALLOW, evaluate(WRITE, "{\"path\":\"file1.txt\"}", settings).action());
    // 显式绝对 workdir 决定 `secret` 的相对坐标，规则按该 workdir 命中。
    assertEquals(
        PermissionAction.DENY,
        evaluate(WRITE, "{\"workdir\":\"/outside\",\"path\":\"secret\"}", settings).action());
    assertEquals(
        PermissionAction.ASK,
        evaluate(
                BASH,
                "{\"command\":\"\"}",
                new ToolSettings(
                    Map.of(BASH, List.of(new PermissionRule("*", PermissionAction.ASK))), false))
            .action());
  }

  /** malformed arguments、null tool name 和 prompt bounds 必须在 preparation 前失败。 */
  @Test
  void rejectsInvalidPermissionContracts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evaluator.evaluate(new PermissionEvaluationContext(WRITE, "[]", ToolSettings.DEFAULT)));
    assertThrows(
        NullPointerException.class,
        () -> new PermissionEvaluationContext(null, "{}", ToolSettings.DEFAULT));
    PermissionEvaluationContext invalidWorkdir =
        new PermissionEvaluationContext(
            WRITE, "{\"path\":\"x\",\"workdir\":\"@\"}", ToolSettings.DEFAULT);
    assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(invalidWorkdir));
    assertEquals("@", evaluator.preview(invalidWorkdir).workdir());
    assertEquals(PermissionAction.ALLOW, PermissionAction.fromValue("allow"));
    assertEquals(PermissionAction.ASK, PermissionAction.fromValue("ask"));
    assertEquals(PermissionAction.DENY, PermissionAction.fromValue("deny"));
    assertThrows(IllegalArgumentException.class, () -> PermissionAction.fromValue("invalid"));
    assertThrows(IllegalArgumentException.class, () -> PermissionAction.fromValue("ALLOW"));
    assertThrows(IllegalArgumentException.class, () -> PermissionAction.fromValue(" allow "));
    assertThrows(IllegalArgumentException.class, () -> PermissionAction.fromValue(null));
    assertThrows(
        IllegalArgumentException.class, () -> new PermissionRule(" ", PermissionAction.ALLOW));
    assertThrows(
        IllegalArgumentException.class, () -> new PermissionPromptPreview("tool", ".", "x\n"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PermissionPromptPreview("tool", "bad\tworkdir", "{}"));
    assertThrows(IllegalArgumentException.class, () -> new PermissionPromptPreview("", ".", "{}"));
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
                settings(WRITE, new PermissionRule("\\!literal.txt", PermissionAction.DENY)))
            .action());
    assertEquals(
        PermissionAction.DENY,
        evaluate(
                WRITE,
                "{\"path\":\"#literal.txt\"}",
                settings(WRITE, new PermissionRule("\\#literal.txt", PermissionAction.DENY)))
            .action());
  }

  private static ToolSettings settings(String toolName, PermissionRule... rules) {
    Map<String, List<PermissionRule>> permission = new LinkedHashMap<>();
    permission.put(toolName, List.of(rules));
    return new ToolSettings(permission, false);
  }

  /** path 规则夹具：自动为 {@code {"path":...}} 形态注入固定 absolute workdir；其余形态原样评估。 */
  private PermissionEvaluator.Evaluation evaluate(
      String tool, String arguments, ToolSettings settings) {
    if (arguments.startsWith("{\"path\":")) {
      return evaluator.evaluate(
          new PermissionEvaluationContext(
              tool, "{\"workdir\":\"/environment\"," + arguments.substring(1), settings));
    }
    return evaluator.evaluate(new PermissionEvaluationContext(tool, arguments, settings));
  }
}

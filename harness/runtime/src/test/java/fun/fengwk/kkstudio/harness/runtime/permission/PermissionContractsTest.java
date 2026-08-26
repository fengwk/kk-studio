package fun.fengwk.kkstudio.harness.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.BaseToolIds;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class PermissionContractsTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ToolSettingsCodec codec = new ToolSettingsCodec(objectMapper);
  private final PermissionEvaluator evaluator =
      new PermissionEvaluator(objectMapper, new BashSurfaceAnalyzer());

  /** codec 接受 canonical list、null permission 和未知字段，并拒绝所有非规范类型。 */
  @Test
  void validatesCanonicalToolSettingsShapes() {
    String canonical =
        codec.canonicalize(
            "{\"other\":1,\"permission\":{\"base.bash\":[{\"pattern\":\"git"
                + " ?\",\"action\":\"allow\"}]}}");
    assertTrue(canonical.contains("\"other\":1"));
    assertEquals(
        PermissionAction.ALLOW, codec.decode(canonical).rulesFor(BaseToolIds.BASH).get(0).action());
    assertFalse(codec.decode("{\"permission\":null}").defaultYolo());

    for (String invalid :
        List.of(
            "[]",
            "not-json",
            "{\"defaultYolo\":1}",
            "{\"permission\":\"invalid\"}",
            "{\"permission\":[]}",
            "{\"permission\":{\"base.bash\":1}}",
            "{\"permission\":{\"base.bash\":{\"*\":1}}}",
            "{\"permission\":{\"base.bash\":[{}]}}",
            "{\"permission\":{\"base.bash\":[{\"pattern\":\"*\",\"action\":\"invalid\"}]}}",
            "{\"permission\":{\"Base.bash\":[]}}",
            "{\"permission\":{\" base.bash\":[]}}",
            "{\"permission\":{\"base.bash \":[]}}")) {
      assertThrows(IllegalArgumentException.class, () -> codec.canonicalize(invalid), invalid);
    }

    IllegalArgumentException invalidKey =
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.decode("{\"permission\":{\" Base.bash\":1}}"));
    assertEquals("permission key must be '*' or a canonical AgentToolId", invalidKey.getMessage());
  }

  @Test
  void acceptsGlobalAndCanonicalIdRulesAndRejectsInvalidKeys() {
    List<PermissionRule> globalRules = List.of(new PermissionRule("*", PermissionAction.ASK));
    List<PermissionRule> toolRules = List.of(new PermissionRule("*", PermissionAction.DENY));
    Map<String, List<PermissionRule>> permission = new LinkedHashMap<>();
    permission.put(PermissionKeyValidator.GLOBAL_KEY, globalRules);
    permission.put(BaseToolIds.BASH.value(), toolRules);

    ToolSettings settings = new ToolSettings(permission, false);

    assertEquals(globalRules, settings.globalRules());
    assertEquals(toolRules, settings.rulesFor(BaseToolIds.BASH));

    for (String invalid : new String[] {null, "", " ", " Base.bash", "Base.bash", "base.bash "}) {
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
        BaseToolIds.WRITE.value(),
        List.of(
            new PermissionRule("*", PermissionAction.ASK),
            new PermissionRule("file?.txt", PermissionAction.ALLOW),
            new PermissionRule("secret", PermissionAction.DENY)));
    ToolSettings settings = new ToolSettings(rules, false);

    assertEquals(
        PermissionAction.ALLOW,
        evaluate(BaseToolIds.WRITE, "{\"path\":\"file1.txt\"}", settings).action());
    // 显式绝对 workdir 是该次调用的 effective workdir：`secret` 解析为其下相对目标并被该 workdir 相对规则命中。
    assertEquals(
        PermissionAction.DENY,
        evaluate(BaseToolIds.WRITE, "{\"workdir\":\"/outside\",\"path\":\"secret\"}", settings)
            .action());
    assertEquals(
        PermissionAction.ASK,
        evaluate(
                BaseToolIds.BASH,
                "{\"command\":\"\"}",
                new ToolSettings(
                    Map.of(
                        BaseToolIds.BASH.value(),
                        List.of(new PermissionRule("*", PermissionAction.ASK))),
                    false))
            .action());
  }

  /** malformed arguments、null tool id 和 prompt bounds 必须在 preparation 前失败。 */
  @Test
  void rejectsInvalidPermissionContracts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evaluator.evaluate(
                new PermissionEvaluationContext(
                    BaseToolIds.WRITE, "[]", Path.of("."), ToolSettings.DEFAULT)));
    assertThrows(
        NullPointerException.class,
        () -> new PermissionEvaluationContext(null, "{}", Path.of("."), ToolSettings.DEFAULT));
    PermissionEvaluationContext invalidWorkdir =
        new PermissionEvaluationContext(
            BaseToolIds.WRITE,
            "{\"path\":\"x\",\"workdir\":\"@\"}",
            Path.of("."),
            ToolSettings.DEFAULT);
    assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(invalidWorkdir));
    assertEquals("<invalid-workdir>", evaluator.preview(invalidWorkdir).workdir());
    assertThrows(IllegalArgumentException.class, () -> PermissionAction.fromValue("invalid"));
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
              BaseToolIds.WRITE,
              new PermissionRule("*", PermissionAction.ALLOW),
              new PermissionRule(invalid, PermissionAction.DENY));
      assertThrows(
          IllegalArgumentException.class,
          () -> evaluate(BaseToolIds.WRITE, "{\"path\":\"logs/a.log\"}", settings),
          invalid);
    }
  }

  /** 转义后的 literal {@code \!}/{@code \#} pattern 交由 JGit 处理并可正常匹配。 */
  @Test
  void supportsEscapedLiteralPathPatterns() {
    assertEquals(
        PermissionAction.DENY,
        evaluate(
                BaseToolIds.WRITE,
                "{\"path\":\"!literal.txt\"}",
                settings(
                    BaseToolIds.WRITE, new PermissionRule("\\!literal.txt", PermissionAction.DENY)))
            .action());
    assertEquals(
        PermissionAction.DENY,
        evaluate(
                BaseToolIds.WRITE,
                "{\"path\":\"#literal.txt\"}",
                settings(
                    BaseToolIds.WRITE, new PermissionRule("\\#literal.txt", PermissionAction.DENY)))
            .action());
  }

  private static ToolSettings settings(AgentToolId toolId, PermissionRule... rules) {
    Map<String, List<PermissionRule>> permission = new LinkedHashMap<>();
    permission.put(toolId.value(), List.of(rules));
    return new ToolSettings(permission, false);
  }

  private PermissionEvaluator.Evaluation evaluate(
      AgentToolId tool, String arguments, ToolSettings settings) {
    return evaluator.evaluate(
        new PermissionEvaluationContext(tool, arguments, Path.of("/environment"), settings));
  }
}

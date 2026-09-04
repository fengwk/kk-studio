package fun.fengwk.kkstudio.harness.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class PermissionEvaluatorTest {
  private static final AgentToolId BROWSER = new AgentToolId("browser");
  private static final AgentToolId WRITE = new AgentToolId("base.write");
  private static final AgentToolId BASH = new AgentToolId("base.bash");
  private static final AgentToolId CUSTOM_EXEC = new AgentToolId("custom.exec");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final PermissionEvaluator evaluator =
      new PermissionEvaluator(objectMapper, new BashSurfaceAnalyzer());

  /** 最后命中规则生效，且 tool-specific 必须在全局规则之后应用。 */
  @Test
  void appliesGlobalThenToolSpecificRulesWithLastMatchWinning() {
    ToolSettings settings =
        settings(
            Map.of(
                "*",
                List.of(new PermissionRule("*", PermissionAction.DENY)),
                WRITE.value(),
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("src/*.java", PermissionAction.ALLOW))));

    assertEquals(
        PermissionAction.ALLOW, evaluate(WRITE, "{\"path\":\"src/Main.java\"}", settings).action());
    assertEquals(
        PermissionAction.ASK, evaluate(WRITE, "{\"path\":\"README.md\"}", settings).action());
  }

  /** 任何工具只要包含 textual command 字段，均应用通用 command-surface 分析。 */
  @Test
  void appliesCommandSurfaceAnalyzerForAnyToolWithCommandField() {
    ToolSettings settings =
        settings(
            Map.of(
                CUSTOM_EXEC.value(),
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("rm *", PermissionAction.DENY),
                    new PermissionRule("git *", PermissionAction.ALLOW))));

    assertEquals(
        PermissionAction.ALLOW,
        evaluate(CUSTOM_EXEC, "{\"command\":\"git status\"}", settings).action());
    assertEquals(
        PermissionAction.DENY,
        evaluate(CUSTOM_EXEC, "{\"command\":\"rm -rf target\"}", settings).action());
    assertEquals(
        PermissionAction.ASK,
        evaluate(CUSTOM_EXEC, "{\"command\":\"npm install\"}", settings).action());
  }

  /** path target 只产生到 effective workdir 的单一规范相对 POSIX 路径；`@` 前缀、绝对输入与 trailing slash hint 归一。 */
  @Test
  void describesSingleEffectiveWorkdirRelativePathTarget() {
    Path workdir = Path.of("/tmp/permission-environment/repo");

    assertEquals(
        new PermissionEvaluator.PathTarget("src/Main.java", false),
        evaluator.describePathTarget("src/Main.java", workdir));
    assertEquals(
        new PermissionEvaluator.PathTarget("src/Main.java", false),
        evaluator.describePathTarget("@src/Main.java", workdir));
    assertEquals(
        new PermissionEvaluator.PathTarget("src/Main.java", false),
        evaluator.describePathTarget("/tmp/permission-environment/repo/src/Main.java", workdir));
    assertEquals(
        new PermissionEvaluator.PathTarget("docs", true),
        evaluator.describePathTarget("docs/", workdir));
    assertEquals(
        new PermissionEvaluator.PathTarget("docs", true),
        evaluator.describePathTarget("docs\\", workdir));
    assertEquals(
        new PermissionEvaluator.PathTarget("..", true),
        evaluator.describePathTarget("../", workdir));
  }

  /** 同一绝对 target 只按 effective workdir 计算规则坐标。 */
  @Test
  void absoluteTargetMatchesOnlyEffectiveWorkdirRelativeRules() {
    Path workdir = Path.of("/tmp/permission-environment/repo");
    ToolSettings settings =
        settings(
            Map.of(
                WRITE.value(),
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("src/Main.java", PermissionAction.ALLOW),
                    new PermissionRule("repo/src/Main.java", PermissionAction.DENY))));

    assertEquals(
        PermissionAction.ALLOW,
        evaluateAtWorkdir(
                WRITE,
                "{\"path\":\"/tmp/permission-environment/repo/src/Main.java\"}",
                settings,
                workdir)
            .action());
    // 工作目录为 /tmp/permission-environment 时，同一绝对路径的相对坐标是 repo/src/Main.java，规则按该基准命中。
    assertEquals(
        PermissionAction.DENY,
        evaluate(WRITE, "{\"path\":\"/tmp/permission-environment/repo/src/Main.java\"}", settings)
            .action());
  }

  /** 显式 workdir 决定 path 解析基准，generic Tool 使用单一 `*` 候选。 */
  @Test
  void matchesExplicitWorkdirAndGenericCandidate() {
    ToolSettings settings =
        settings(
            Map.of(
                WRITE.value(),
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("src/*.java", PermissionAction.ALLOW)),
                BROWSER.value(),
                List.of(new PermissionRule("*", PermissionAction.DENY))));

    assertEquals(
        PermissionAction.ALLOW,
        evaluate(WRITE, "{\"workdir\":\"repo\",\"path\":\"src/Main.java\"}", settings).action());
    PermissionEvaluator.Evaluation atPrefixed =
        evaluate(WRITE, "{\"workdir\":\"@repo\",\"path\":\"src/Main.java\"}", settings);
    assertEquals(PermissionAction.ALLOW, atPrefixed.action());
    assertEquals("repo", atPrefixed.promptPreview().workdir());
    assertEquals(PermissionAction.DENY, evaluate(BROWSER, "{}", settings).action());
  }

  /** `~`、`$HOME` 与 `${HOME}` 路径快捷方式在 effective workdir 解析前展开。 */
  @Test
  void expandsSupportedHomeShortcuts() {
    Path home = Path.of(System.getProperty("user.home"));
    ToolSettings settings =
        settings(
            Map.of(
                WRITE.value(), List.of(new PermissionRule("secret.txt", PermissionAction.DENY))));

    assertEquals(
        new PermissionEvaluator.PathTarget("secret.txt", false),
        evaluator.describePathTarget("${HOME}/secret.txt", home));
    assertEquals(
        PermissionAction.DENY,
        evaluateAtWorkdir(WRITE, "{\"path\":\"${HOME}/secret.txt\"}", settings, home).action());
    assertEquals(
        PermissionAction.DENY,
        evaluateAtWorkdir(WRITE, "{\"path\":\"~/secret.txt\"}", settings, home).action());
    assertEquals(
        PermissionAction.DENY,
        evaluateAtWorkdir(WRITE, "{\"path\":\"$HOME/secret.txt\"}", settings, home).action());
  }

  /**
   * path pattern 使用 JGit gitignore 语义：`*` 不跨 `/`、`**` 跨层级、basename 任意层级、leading `/` 锚定、directory
   * rule 覆盖 descendant。
   */
  @Test
  void appliesJGitGitignorePathSemantics() {
    assertEquals(
        PermissionAction.ASK,
        evaluate(WRITE, "{\"path\":\"docs/deep/a.md\"}", deny("docs/*.md")).action());
    assertEquals(
        PermissionAction.DENY,
        evaluate(WRITE, "{\"path\":\"docs/a.md\"}", deny("docs/*.md")).action());
    assertEquals(
        PermissionAction.DENY,
        evaluate(WRITE, "{\"path\":\"docs/a.md\"}", deny("docs/**/*.md")).action());
    assertEquals(
        PermissionAction.DENY,
        evaluate(WRITE, "{\"path\":\"docs/deep/a.md\"}", deny("docs/**/*.md")).action());
    assertEquals(
        PermissionAction.DENY, evaluate(WRITE, "{\"path\":\"a/b.tmp\"}", deny("*.tmp")).action());
    assertEquals(
        PermissionAction.DENY,
        evaluate(WRITE, "{\"path\":\"sub/README.md\"}", deny("README.md")).action());
    assertEquals(
        PermissionAction.DENY,
        evaluate(WRITE, "{\"path\":\"root-only.txt\"}", deny("/root-only.txt")).action());
    assertEquals(
        PermissionAction.ASK,
        evaluate(WRITE, "{\"path\":\"nested/root-only.txt\"}", deny("/root-only.txt")).action());
    assertEquals(
        PermissionAction.DENY, evaluate(WRITE, "{\"path\":\"docs/a\"}", deny("docs/")).action());
    assertEquals(
        PermissionAction.ASK, evaluate(WRITE, "{\"path\":\"docs\"}", deny("docs/")).action());
    assertEquals(
        PermissionAction.DENY, evaluate(WRITE, "{\"path\":\"docs/\"}", deny("docs/")).action());
  }

  /** permission prompt 参数必须单行且最多 120 字符，无 UI 时可原样持久等待。 */
  @Test
  void createsCompactPersistentPromptPreview() {
    String command = "x".repeat(150) + "\nnext";
    PermissionEvaluator.Evaluation evaluation =
        evaluate(
            BASH,
            "{\"command\":\"" + command.replace("\n", "\\n") + "\"}",
            settings(Map.of(BASH.value(), List.of(new PermissionRule("*", PermissionAction.ASK)))));

    assertEquals(PermissionAction.ASK, evaluation.action());
    assertTrue(evaluation.promptPreview().workdir().endsWith("(default)"));
    assertEquals(120, evaluation.promptPreview().arguments().length());
    assertTrue(evaluation.promptPreview().arguments().endsWith("..."));
    assertEquals(
        "{\"path\":\"README.md\"}",
        evaluate(
                WRITE,
                " { \"path\" : \"README.md\" } ",
                settings(
                    Map.of(WRITE.value(), List.of(new PermissionRule("*", PermissionAction.ASK)))))
            .promptPreview()
            .arguments());
  }

  /** 规范的 ToolSettings 配置在 canonicalize 与 decode 时保持结构一致。 */
  @Test
  void canonicalizesAndDecodesToolSettings() throws Exception {
    ToolSettingsCodec codec = new ToolSettingsCodec(objectMapper);
    String canonical =
        codec.canonicalize(
            "{\"permission\":{\"base.write\":[{\"pattern\":\"*\",\"action\":\"ask\"}],"
                + "\"base.bash\":[{\"pattern\":\"*\",\"action\":\"ask\"},{\"pattern\":\"git *\",\"action\":\"allow\"}]},"
                + "\"defaultYolo\":true}");
    JsonNode root = objectMapper.readTree(canonical);

    assertTrue(root.path("defaultYolo").asBoolean());
    assertTrue(root.path("permission").path("base.write").isArray());
    assertEquals("*", root.path("permission").path("base.write").get(0).path("pattern").asText());
    assertEquals(
        "git *", root.path("permission").path("base.bash").get(1).path("pattern").asText());
    assertEquals(PermissionAction.ALLOW, codec.decode(canonical).rulesFor(BASH).get(1).action());

    JsonNode global =
        objectMapper.readTree(
            codec.canonicalize(
                "{\"permission\":{\"*\":[{\"pattern\":\"*\",\"action\":\"ask\"}]}}"));
    assertEquals("*", global.path("permission").path("*").get(0).path("pattern").asText());
    assertEquals("ask", global.path("permission").path("*").get(0).path("action").asText());
  }

  private static ToolSettings deny(String deniedPattern) {
    return settings(
        Map.of(
            WRITE.value(),
            List.of(
                new PermissionRule("*", PermissionAction.ASK),
                new PermissionRule(deniedPattern, PermissionAction.DENY))));
  }

  private PermissionEvaluator.Evaluation evaluate(
      AgentToolId toolId, String arguments, ToolSettings settings) {
    return evaluateAtWorkdir(toolId, arguments, settings, Path.of("/tmp/permission-environment"));
  }

  private PermissionEvaluator.Evaluation evaluateAtWorkdir(
      AgentToolId toolId, String arguments, ToolSettings settings, Path workdir) {
    return evaluator.evaluate(
        new PermissionEvaluationContext(toolId, arguments, workdir, settings));
  }

  private static ToolSettings settings(Map<String, List<PermissionRule>> rules) {
    return new ToolSettings(rules, false);
  }
}

package fun.fengwk.kkstudio.harness.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class PermissionEvaluatorTest {
  private static final String BROWSER = "browser";
  private static final String WRITE = "write";
  private static final String BASH = "bash";
  private static final String CUSTOM_EXEC = "custom_exec";

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
                WRITE,
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
                CUSTOM_EXEC,
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

  /**
   * path target 是纯词法计算：只归一绝对输入、`\\` 分隔符、`.`/`..` 折叠与 trailing slash hint，不读取 Backend HOME 或文件系统，也不把
   * UI 展示前缀解释成路径语法。
   */
  @Test
  void describesSingleEffectiveWorkdirRelativePathTargetLexically() {
    String workdir = "/tmp/permission-environment/repo";

    assertEquals(
        new PermissionEvaluator.PathTarget("src/Main.java", false),
        evaluator.describePathTarget("src/Main.java", workdir));
    assertEquals(
        new PermissionEvaluator.PathTarget("@src/Main.java", false),
        evaluator.describePathTarget("@src/Main.java", workdir));
    assertEquals(
        new PermissionEvaluator.PathTarget("src/Main.java", false),
        evaluator.describePathTarget("/tmp/permission-environment/repo/src/Main.java", workdir));
    assertEquals(
        new PermissionEvaluator.PathTarget("src/Main.java", false),
        evaluator.describePathTarget("src/./sub/../Main.java", workdir));
    assertEquals(
        new PermissionEvaluator.PathTarget("docs", true),
        evaluator.describePathTarget("docs/", workdir));
    assertEquals(
        new PermissionEvaluator.PathTarget("docs", true),
        evaluator.describePathTarget("docs\\", workdir));
    assertEquals(
        new PermissionEvaluator.PathTarget("..", true),
        evaluator.describePathTarget("../", workdir));
    // Windows 目标形态按自身 root 词法解析，不受 Backend OS 影响。
    assertEquals(
        new PermissionEvaluator.PathTarget("src/Main.java", false),
        evaluator.describePathTarget("C:\\repo\\src\\Main.java", "c:/repo"));
    // 跨 Windows root 保留 root-qualified 坐标；绝对 target 仍可按 Daemon 原生语义执行。
    assertEquals(
        new PermissionEvaluator.PathTarget("D:/other/a.txt", false),
        evaluator.describePathTarget("D:\\other\\a.txt", "C:/repo"));
    assertEquals(
        new PermissionEvaluator.PathTarget("src/Main.java", false),
        evaluator.describePathTarget(
            "\\\\server\\share\\repo\\src\\Main.java", "//server/share/repo"));
    assertEquals(
        new PermissionEvaluator.PathTarget("//other/share/a.txt", false),
        evaluator.describePathTarget("\\\\other\\share\\a.txt", "//server/share/repo"));
    // Unix workdir 决定目标路径族：C:/ 是合法的字面相对 segment，多个前导 slash 仍是 Unix root。
    assertEquals(
        new PermissionEvaluator.PathTarget("C:/literal.txt", false),
        evaluator.describePathTarget("C:/literal.txt", "/srv/repo"));
    assertEquals(
        new PermissionEvaluator.PathTarget("../../var/data.txt", false),
        evaluator.describePathTarget("//var/data.txt", "/srv/repo"));
  }

  /** 跨 Windows root 的绝对 target 仍按 basename/root-qualified 规则评估，不得在审批前被当成非法路径。 */
  @Test
  void matchesCrossRootWindowsAbsoluteTarget() {
    ToolSettings settings =
        settings(
            Map.of(
                WRITE,
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("a.txt", PermissionAction.DENY))));

    assertEquals(
        PermissionAction.DENY,
        evaluateRaw(WRITE, "{\"workdir\":\"C:/repo\",\"path\":\"D:/other/a.txt\"}", settings)
            .action());
    assertEquals(
        PermissionAction.DENY,
        evaluateRaw(
                WRITE,
                "{\"workdir\":\"//server/share/repo\",\"path\":\"//other/share/a.txt\"}",
                settings)
            .action());
  }

  /** 同一绝对 target 只按本次调用 explicit workdir 计算规则坐标。 */
  @Test
  void absoluteTargetMatchesOnlyEffectiveWorkdirRelativeRules() {
    String workdir = "/tmp/permission-environment/repo";
    ToolSettings settings =
        settings(
            Map.of(
                WRITE,
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("src/Main.java", PermissionAction.ALLOW),
                    new PermissionRule("repo/src/Main.java", PermissionAction.DENY))));

    assertEquals(
        PermissionAction.ALLOW,
        evaluateRaw(
                WRITE,
                "{\"workdir\":\""
                    + workdir
                    + "\",\"path\":\"/tmp/permission-environment/repo/src/Main.java\"}",
                settings)
            .action());
    // 对照：workdir 为父目录 /tmp/permission-environment 时，同一绝对路径的相对坐标是 repo/src/Main.java。
    assertEquals(
        PermissionAction.DENY,
        evaluate(WRITE, "{\"path\":\"/tmp/permission-environment/repo/src/Main.java\"}", settings)
            .action());
  }

  /** 显式 absolute workdir 决定 path 解析基准，generic Tool 使用单一 `*` 候选。 */
  @Test
  void matchesExplicitWorkdirAndGenericCandidate() {
    ToolSettings settings =
        settings(
            Map.of(
                WRITE,
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("src/*.java", PermissionAction.ALLOW)),
                BROWSER,
                List.of(new PermissionRule("*", PermissionAction.DENY))));

    assertEquals(
        PermissionAction.ALLOW,
        evaluateRaw(WRITE, "{\"workdir\":\"/srv/repo\",\"path\":\"src/Main.java\"}", settings)
            .action());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evaluateRaw(
                WRITE, "{\"workdir\":\"@/srv/repo\",\"path\":\"src/Main.java\"}", settings));
    assertEquals(PermissionAction.DENY, evaluateRaw(BROWSER, "{}", settings).action());
  }

  /** 不展开 `~`/`$HOME`/`${HOME}`：它们只是普通 relative segment，保持字面量，绝不作 Backend HOME 展开。 */
  @Test
  void neverExpandsHomeShortcuts() {
    ToolSettings settings =
        settings(
            Map.of(
                WRITE,
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("secret.txt", PermissionAction.DENY))));

    // 非展开的确切证据：占位符作为字面 segment 保留在规则坐标里，而不是被替换为 /srv/workspace。
    assertEquals(
        new PermissionEvaluator.PathTarget("${HOME}/secret.txt", false),
        evaluator.describePathTarget("${HOME}/secret.txt", "/srv/workspace"));
    // 对照：真正叫 secret.txt 的相对 path 命中 basename 规则 DENY（gitignore 语义，与展开无关）。
    assertEquals(
        PermissionAction.DENY,
        evaluateRaw(WRITE, "{\"workdir\":\"/srv/workspace\",\"path\":\"secret.txt\"}", settings)
            .action());
    assertEquals(
        new PermissionEvaluator.PathTarget("~/secret.txt", false),
        evaluator.describePathTarget("~/secret.txt", "/srv/workspace"));
    assertEquals(
        new PermissionEvaluator.PathTarget("$HOME/secret.txt", false),
        evaluator.describePathTarget("$HOME/secret.txt", "/srv/workspace"));
  }

  /** 非编码工具即使恰有 path 参数也不需要 workdir；显式提供的非法 workdir 不会被修正或回退。 */
  @Test
  void doesNotInferWorkdirSemanticsFromGenericPathField() {
    ToolSettings settings =
        settings(
            Map.of(
                WRITE,
                List.of(new PermissionRule("*", PermissionAction.ASK)),
                BROWSER,
                List.of(new PermissionRule("*", PermissionAction.ASK))));

    PermissionEvaluator.Evaluation generic =
        evaluateRaw(BROWSER, "{\"path\":\"remote/object\"}", settings);
    assertEquals(PermissionAction.ASK, generic.action());
    assertNull(generic.promptPreview().workdir());
    assertThrows(
        IllegalArgumentException.class,
        () -> evaluateRaw(WRITE, "{\"workdir\":\" \",\"path\":\"src/Main.java\"}", settings));
  }

  /** 没有 path/command 的工具（例如 load_skill、MCP）preview 不带 workdir，也不做任何目录推断。 */
  @Test
  void previewOmitsWorkdirWhenInvocationHasNone() {
    PermissionEvaluator.Evaluation evaluation =
        evaluate(
            BROWSER,
            "{\"skill\":\"web-search\"}",
            settings(Map.of(BROWSER, List.of(new PermissionRule("*", PermissionAction.ASK)))));

    assertEquals(PermissionAction.ASK, evaluation.action());
    assertNull(evaluation.promptPreview().workdir());
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
            settings(Map.of(BASH, List.of(new PermissionRule("*", PermissionAction.ASK)))));

    assertEquals(PermissionAction.ASK, evaluation.action());
    assertNull(evaluation.promptPreview().workdir());
    assertEquals(120, evaluation.promptPreview().arguments().length());
    assertTrue(evaluation.promptPreview().arguments().endsWith("..."));
    // arguments preview 重新序列化为紧凑单行：输入中的空白不进入 preview。
    assertEquals(
        "{\"workdir\":\"/srv/repo\",\"path\":\"README.md\"}",
        evaluateRaw(
                WRITE,
                " { \"workdir\" : \"/srv/repo\" , \"path\" : \"README.md\" } ",
                settings(Map.of(WRITE, List.of(new PermissionRule("*", PermissionAction.ASK)))))
            .promptPreview()
            .arguments());
  }

  /** 规范的 ToolSettings 配置在 canonicalize 与 decode 时保持结构一致。 */
  @Test
  void canonicalizesAndDecodesToolSettings() throws Exception {
    ToolSettingsCodec codec = new ToolSettingsCodec(objectMapper);
    String canonical =
        codec.canonicalize(
            "{\"permission\":{\"write\":[{\"pattern\":\"*\",\"action\":\"ask\"}],"
                + "\"bash\":[{\"pattern\":\"*\",\"action\":\"ask\"},{\"pattern\":\"git *\",\"action\":\"allow\"}]},"
                + "\"defaultYolo\":true}");
    JsonNode root = objectMapper.readTree(canonical);

    assertTrue(root.path("defaultYolo").asBoolean());
    assertTrue(root.path("permission").path("write").isArray());
    assertEquals("*", root.path("permission").path("write").get(0).path("pattern").asText());
    assertEquals("git *", root.path("permission").path("bash").get(1).path("pattern").asText());
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
            WRITE,
            List.of(
                new PermissionRule("*", PermissionAction.ASK),
                new PermissionRule(deniedPattern, PermissionAction.DENY))));
  }

  /** path 规则夹具：自动为 {@code {"path":...}} 形态注入固定 absolute workdir，让各用例只表达 pattern 关注点。 */
  private PermissionEvaluator.Evaluation evaluate(
      String toolName, String arguments, ToolSettings settings) {
    if (arguments.startsWith("{\"path\":")) {
      return evaluateRaw(
          toolName,
          "{\"workdir\":\"/tmp/permission-environment\"," + arguments.substring(1),
          settings);
    }
    return evaluateRaw(toolName, arguments, settings);
  }

  /** 按原样评估：需要直接断言缺失/非法 workdir 的用例走这里。 */
  private PermissionEvaluator.Evaluation evaluateRaw(
      String toolName, String arguments, ToolSettings settings) {
    return evaluator.evaluate(new PermissionEvaluationContext(toolName, arguments, settings));
  }

  private static ToolSettings settings(Map<String, List<PermissionRule>> rules) {
    return new ToolSettings(rules, false);
  }
}

package fun.fengwk.kkstudio.platform.harness.tool.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class PermissionEvaluatorTest {
  private static final String BROWSER = "browser";
  private static final String WRITE = "write";
  private static final String BASH = "bash";
  private static final String CUSTOM_EXEC = "custom_exec";

  private final PermissionEvaluator evaluator = new PermissionEvaluator(new BashSurfaceAnalyzer());

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

    assertEquals(PermissionAction.ALLOW, evaluate(WRITE, "{\"path\":\"src/Main.java\"}", settings));
    assertEquals(PermissionAction.ASK, evaluate(WRITE, "{\"path\":\"README.md\"}", settings));
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
        PermissionAction.ALLOW, evaluate(CUSTOM_EXEC, "{\"command\":\"git status\"}", settings));
    assertEquals(
        PermissionAction.DENY, evaluate(CUSTOM_EXEC, "{\"command\":\"rm -rf target\"}", settings));
    assertEquals(
        PermissionAction.ASK, evaluate(CUSTOM_EXEC, "{\"command\":\"npm install\"}", settings));
  }

  /**
   * path target 是纯词法计算：只归一绝对输入、`\\` 分隔符、`.`/`..` 折叠与 trailing slash hint，Unix 去掉 root 前缀、Windows 保留
   * root-qualified，不读取 Backend HOME 或文件系统。
   */
  @Test
  void describesAbsolutePathTargetLexically() {
    assertEquals(
        new PermissionEvaluator.PathTarget("tmp/permission-environment/repo/src/Main.java", false),
        evaluator.describePathTarget("/tmp/permission-environment/repo/src/Main.java"));
    assertEquals(
        new PermissionEvaluator.PathTarget("tmp/permission-environment/repo/src/Main.java", false),
        evaluator.describePathTarget("/tmp/permission-environment/repo/src/./sub/../Main.java"));
    assertEquals(
        new PermissionEvaluator.PathTarget("srv/repo/docs", true),
        evaluator.describePathTarget("/srv/repo/docs/"));
    assertEquals(
        new PermissionEvaluator.PathTarget("srv/repo/docs", true),
        evaluator.describePathTarget("\\srv\\repo\\docs\\"));
    // Windows drive/UNC 保留 root-qualified 形态，不受 Backend OS 影响。
    assertEquals(
        new PermissionEvaluator.PathTarget("C:/repo/src/Main.java", false),
        evaluator.describePathTarget("C:\\repo\\src\\Main.java"));
    assertEquals(
        new PermissionEvaluator.PathTarget("D:/other/a.txt", false),
        evaluator.describePathTarget("D:\\other\\a.txt"));
    assertEquals(
        new PermissionEvaluator.PathTarget("//server/share/repo/src/Main.java", false),
        evaluator.describePathTarget("\\\\server\\share\\repo\\src\\Main.java"));
    // 非绝对形态不是路径坐标：相对文本、URI scheme、drive-relative 都返回 false 并拒绝描述。
    assertFalse(LexicalTargetPath.isAbsolute("src/Main.java"));
    assertFalse(LexicalTargetPath.isAbsolute("C:foo"));
    assertFalse(LexicalTargetPath.isAbsolute("s3:/bucket/a.txt"));
    assertFalse(LexicalTargetPath.isAbsolute("file:///srv/a.txt"));
    assertFalse(LexicalTargetPath.isAbsolute("https://example.com/a.txt"));
    assertThrows(
        IllegalArgumentException.class, () -> evaluator.describePathTarget("src/Main.java"));
  }

  /** Windows drive/UNC 绝对 target 按 basename/root-qualified 规则评估，不得在审批前被当成非法路径。 */
  @Test
  void matchesWindowsAbsoluteTarget() {
    ToolSettings settings =
        settings(
            Map.of(
                WRITE,
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("a.txt", PermissionAction.DENY))));

    assertEquals(
        PermissionAction.DENY, evaluateRaw(WRITE, "{\"path\":\"D:/other/a.txt\"}", settings));
    assertEquals(
        PermissionAction.DENY, evaluateRaw(WRITE, "{\"path\":\"//other/share/a.txt\"}", settings));
  }

  /** 绝对 target 以 filesystem root 为锚：只有 root-relative 坐标命中，使用其它锚点的规则不命中。 */
  @Test
  void absoluteTargetMatchesFilesystemRootRelativeRules() {
    ToolSettings settings =
        settings(
            Map.of(
                WRITE,
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule(
                        "tmp/permission-environment/repo/src/Main.java", PermissionAction.ALLOW),
                    new PermissionRule("repo/src/Main.java", PermissionAction.DENY))));

    assertEquals(
        PermissionAction.ALLOW,
        evaluateRaw(
            WRITE, "{\"path\":\"/tmp/permission-environment/repo/src/Main.java\"}", settings));
    // 锚点不同的同名后缀不命中 root-relative 规则：坐标是 repo/src/Main.java。
    assertEquals(
        PermissionAction.DENY, evaluateRaw(WRITE, "{\"path\":\"/repo/src/Main.java\"}", settings));
  }

  /** 绝对 path 按 filesystem-root 坐标匹配 path 规则；相对 path 与 URI 不是路径坐标，回落 wildcard 候选 `*`。 */
  @Test
  void absolutePathUsesPathRulesWhileNonPathFallsBackToWildcard() {
    ToolSettings settings =
        settings(
            Map.of(
                WRITE,
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("**/src/*.java", PermissionAction.ALLOW)),
                BROWSER,
                List.of(new PermissionRule("*", PermissionAction.DENY))));

    assertEquals(
        PermissionAction.ALLOW,
        evaluateRaw(WRITE, "{\"path\":\"/srv/repo/src/Main.java\"}", settings));
    // 相对 path 只命中 wildcard `*` → ASK，绝不按 `**/src/*.java` 放行。
    assertEquals(
        PermissionAction.ASK,
        evaluateRaw(WRITE, "{\"workdir\":\"/srv/repo\",\"path\":\"src/Main.java\"}", settings));
    assertEquals(
        PermissionAction.ASK, evaluateRaw(WRITE, "{\"path\":\"resource://files/abc\"}", settings));
    assertEquals(PermissionAction.DENY, evaluateRaw(BROWSER, "{}", settings));
  }

  /** 不展开 `~`/`$HOME`/`${HOME}`：它们不是绝对路径，回落 wildcard，绝不作 Backend HOME 展开。 */
  @Test
  void neverExpandsHomeShortcuts() {
    ToolSettings settings =
        settings(
            Map.of(
                WRITE,
                List.of(
                    new PermissionRule("*", PermissionAction.ASK),
                    new PermissionRule("secret.txt", PermissionAction.DENY))));

    assertFalse(LexicalTargetPath.isAbsolute("~/.ssh/id_rsa"));
    assertEquals(
        PermissionAction.ASK, evaluateRaw(WRITE, "{\"path\":\"~/.ssh/id_rsa\"}", settings));
    assertEquals(
        PermissionAction.ASK, evaluateRaw(WRITE, "{\"path\":\"${HOME}/secret.txt\"}", settings));
    assertEquals(
        PermissionAction.ASK, evaluateRaw(WRITE, "{\"path\":\"$HOME/secret.txt\"}", settings));
    // 绝对路径里的 basename 规则照常命中（gitignore 语义，与展开无关）。
    assertEquals(
        PermissionAction.DENY,
        evaluateRaw(WRITE, "{\"path\":\"/home/user/secret.txt\"}", settings));
  }

  /** 通用 Tool 恰好带 non-absolute path 字段时也回落 wildcard，不套用 path 规则。 */
  @Test
  void doesNotInferPathSemanticsFromGenericPathField() {
    ToolSettings settings =
        settings(
            Map.of(
                WRITE,
                List.of(new PermissionRule("*", PermissionAction.ASK)),
                BROWSER,
                List.of(new PermissionRule("*", PermissionAction.ASK))));

    assertEquals(
        PermissionAction.ASK, evaluateRaw(BROWSER, "{\"path\":\"remote/object\"}", settings));
  }

  /**
   * path pattern 使用 JGit gitignore 语义：`*` 不跨 `/`、`**` 跨层级、basename 任意层级、leading `/` 锚定、directory
   * rule 覆盖 descendant。
   */
  @Test
  void appliesJGitGitignorePathSemantics() {
    assertEquals(
        PermissionAction.ASK, evaluate(WRITE, "{\"path\":\"docs/deep/a.md\"}", deny("docs/*.md")));
    assertEquals(
        PermissionAction.DENY, evaluate(WRITE, "{\"path\":\"docs/a.md\"}", deny("docs/*.md")));
    assertEquals(
        PermissionAction.DENY, evaluate(WRITE, "{\"path\":\"docs/a.md\"}", deny("docs/**/*.md")));
    assertEquals(
        PermissionAction.DENY,
        evaluate(WRITE, "{\"path\":\"docs/deep/a.md\"}", deny("docs/**/*.md")));
    assertEquals(PermissionAction.DENY, evaluate(WRITE, "{\"path\":\"a/b.tmp\"}", deny("*.tmp")));
    assertEquals(
        PermissionAction.DENY, evaluate(WRITE, "{\"path\":\"sub/README.md\"}", deny("README.md")));
    assertEquals(
        PermissionAction.DENY,
        evaluate(WRITE, "{\"path\":\"root-only.txt\"}", deny("/root-only.txt")));
    assertEquals(
        PermissionAction.ASK,
        evaluate(WRITE, "{\"path\":\"nested/root-only.txt\"}", deny("/root-only.txt")));
    assertEquals(PermissionAction.DENY, evaluate(WRITE, "{\"path\":\"docs/a\"}", deny("docs/")));
    assertEquals(PermissionAction.ASK, evaluate(WRITE, "{\"path\":\"docs\"}", deny("docs/")));
    assertEquals(PermissionAction.DENY, evaluate(WRITE, "{\"path\":\"docs/\"}", deny("docs/")));
  }

  private static ToolSettings deny(String deniedPattern) {
    return settings(
        Map.of(
            WRITE,
            List.of(
                new PermissionRule("*", PermissionAction.ASK),
                new PermissionRule(deniedPattern, PermissionAction.DENY))));
  }

  /** path 规则夹具：把相对 fixture 根到 filesystem root（坐标即 fixture 文本），让各用例只表达 pattern 关注点。 */
  private PermissionAction evaluate(String toolName, String arguments, ToolSettings settings) {
    if (arguments.startsWith("{\"path\":")) {
      return evaluateRaw(
          toolName, "{\"path\":\"/" + arguments.substring("{\"path\":\"".length()), settings);
    }
    return evaluateRaw(toolName, arguments, settings);
  }

  /** 按原样评估：需要直接断言相对 path 回落或非 path 坐标的用例走这里。 */
  private PermissionAction evaluateRaw(String toolName, String arguments, ToolSettings settings) {
    return evaluator.evaluate(new PermissionEvaluationContext(toolName, arguments, settings));
  }

  private static ToolSettings settings(Map<String, List<PermissionRule>> rules) {
    return new ToolSettings(rules);
  }
}

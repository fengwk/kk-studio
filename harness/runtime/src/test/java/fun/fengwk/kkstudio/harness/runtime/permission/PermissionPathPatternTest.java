package fun.fengwk.kkstudio.harness.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 公共 {@link PermissionPathPattern} 的事实测试：compile+validate 语义（negation/comment/空/无效 fail-fast，转义
 * literal 放行）与 gitignore 匹配语义（basename、{@code *}/{@code **}、根锚定、directory rule）和持久化安全子集校验完全相同。
 */
class PermissionPathPatternTest {

  /** 与持久化边界一致：negation、comment、空白与 JGit 无法解析的无效形态都必须被拒绝。 */
  @Test
  void rejectsNegationCommentBlankAndInvalidPatterns() {
    for (String invalid :
        List.of("!logs/", "#comment", " ", "", "[unclosed-class", "\\", "logs/\\")) {
      assertThrows(
          IllegalArgumentException.class, () -> PermissionPathPattern.of(invalid), invalid);
      assertThrows(
          IllegalArgumentException.class, () -> PermissionPathPattern.validate(invalid), invalid);
    }
    assertThrows(NullPointerException.class, () -> PermissionPathPattern.of(null));
    assertThrows(NullPointerException.class, () -> PermissionPathPattern.validate(null));
  }

  /** 转义后的 literal（{@code \!}/{@code \#}）不是 negation/comment，放行且可正常匹配。 */
  @Test
  void supportsEscapedLiteralPatterns() {
    assertTrue(PermissionPathPattern.of("\\!literal.txt").matches("!literal.txt", false));
    assertTrue(PermissionPathPattern.of("\\#literal.txt").matches("#literal.txt", false));
    assertFalse(PermissionPathPattern.of("\\#literal.txt").matches("other.txt", false));
  }

  /** gitignore 语义：basename pattern 匹配任意层级；{@code *} 不跨 {@code /}；{@code **} 跨层级。 */
  @Test
  void matchesWithGitignoreSemantics() {
    PermissionPathPattern buildLogs = PermissionPathPattern.of("build.log");
    assertTrue(buildLogs.matches("build.log", false));
    assertTrue(buildLogs.matches("a/build.log", false));
    assertTrue(buildLogs.matches("a/b/c/build.log", false));
    assertFalse(buildLogs.matches("build.txt", false));

    // basename `*.tmp` 匹配任意层级的同名 basename（与 evaluator 长期契约一致）。
    PermissionPathPattern basenameStar = PermissionPathPattern.of("*.tmp");
    assertTrue(basenameStar.matches("x.tmp", false));
    assertTrue(basenameStar.matches("a/x.tmp", false), "basename star matches at any depth");

    // 带斜杠 pattern 的 `*` 不跨 `/`；无前导斜杠不入其它目录。
    PermissionPathPattern docsStar = PermissionPathPattern.of("docs/*.md");
    assertTrue(docsStar.matches("docs/a.md", false));
    assertFalse(docsStar.matches("docs/deep/a.md", false), "star must not cross '/'");
    assertFalse(docsStar.matches("a/docs/a.md", false));

    PermissionPathPattern doubleStar = PermissionPathPattern.of("**/node_modules/**");
    assertTrue(doubleStar.matches("node_modules/a.js", false));
    assertTrue(doubleStar.matches("a/b/node_modules/c.js", false));
    assertFalse(doubleStar.matches("a.js", false));
  }

  /** root-anchored pattern 只匹配 workdir 相对路径的根；directory-only pattern 可覆盖 descendant。 */
  @Test
  void keepsLeadingSlashAnchoringAndDirectoryRuleCoverage() {
    PermissionPathPattern anchored = PermissionPathPattern.of("/top.log");
    assertTrue(anchored.matches("top.log", false));
    assertFalse(anchored.matches("a/top.log", false));

    PermissionPathPattern docs = PermissionPathPattern.of("docs/");
    assertTrue(docs.matches("docs", true));
    assertTrue(docs.matches("docs/a.txt", false), "directory rule must cover descendants");
    assertTrue(docs.matches("a/docs/b.txt", false), "directory-only rule matches at any depth");
    assertFalse(docs.matches("docs.txt", false));
  }
}

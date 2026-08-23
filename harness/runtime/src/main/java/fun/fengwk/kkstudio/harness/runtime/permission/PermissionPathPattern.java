package fun.fengwk.kkstudio.harness.runtime.permission;

import org.eclipse.jgit.ignore.FastIgnoreRule;

import java.util.Objects;

/**
 * 单条 permission path pattern 的不可变编译结果，唯一拥有 JGit gitignore 语义（{@link FastIgnoreRule}）的公共 API。
 *
 * <p>pattern 以 raw 文本交给 {@link FastIgnoreRule}（public、immutable、thread-safe）；{@link #of(String)} 即
 * compile + validate（fail-fast），匹配使用两参数 {@link FastIgnoreRule#isMatch(String, boolean)}：basename
 * pattern 匹配任意层级， directory-only pattern（如 {@code docs/}）可覆盖 descendant（如 {@code docs/a}）。
 *
 * <p>permission action 已表达 allow/ask/deny，因此 negation pattern（{@code !} 前缀）会引入双重否定，与 comment/空/无效
 * pattern 一样在 {@link #of(String)} 直接拒绝，绝不静默失效；持久化的安全子集（有效非否定 pattern）也经 {@link #validate(String)}
 * 复用同一事实语义。
 *
 * <p>{@link FastIgnoreRule} 只在本类（runtime 模块）内被直接使用，platform 等上层模块不得直接 import JGit，而是使用本公共 API。
 */
public final class PermissionPathPattern {

  private final FastIgnoreRule rule;

  private PermissionPathPattern(FastIgnoreRule rule) {
    this.rule = rule;
  }

  /**
   * 校验并编译一条 path pattern；negation、空白、comment-only 或 JGit 无法解析的无效形态直接抛 {@link
   * IllegalArgumentException}（fail-fast，绝不静默失效）。
   *
   * @return 已编译的不可变 pattern
   */
  public static PermissionPathPattern of(String pattern) {
    return new PermissionPathPattern(compile(pattern));
  }

  /** 只校验（不保留编译结果），供持久化边界复用同一事实语义；失败行为与 {@link #of(String)} 完全一致。 */
  public static void validate(String pattern) {
    compile(pattern);
  }

  /** 用 gitignore 语义判断相对 POSIX 路径是否命中；{@code directory} 为 true 时该目标被视为目录。 */
  public boolean matches(String relativePosixPath, boolean directory) {
    Objects.requireNonNull(relativePosixPath, "relativePosixPath");
    return rule.isMatch(relativePosixPath, directory);
  }

  private static FastIgnoreRule compile(String pattern) {
    Objects.requireNonNull(pattern, "pattern");
    FastIgnoreRule rule = new FastIgnoreRule(pattern);
    if (rule.getNegation()) {
      throw new IllegalArgumentException(
          "path permission pattern must not start with '!': \"" + pattern + "\"");
    }
    if (pattern.isBlank() || rule.isEmpty()) {
      throw new IllegalArgumentException(
          "path permission pattern is empty, comment-only or invalid: \"" + pattern + "\"");
    }
    return rule;
  }
}

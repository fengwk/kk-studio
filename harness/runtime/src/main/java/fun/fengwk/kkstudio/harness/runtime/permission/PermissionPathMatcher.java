package fun.fengwk.kkstudio.harness.runtime.permission;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单条 permission path pattern 的匹配封装：compile + validate 由公共 {@link PermissionPathPattern} 唯一承担，本类只按
 * pattern 文本缓存编译结果并委托匹配（JGit gitignore 语义）。
 *
 * <p>JGit 直接依赖只存在于 {@link PermissionPathPattern}（runtime 模块内），本类不直接 import JGit。
 */
final class PermissionPathMatcher {

  private final Map<String, PermissionPathPattern> compiledPatterns = new ConcurrentHashMap<>();

  boolean matches(String pattern, PermissionEvaluator.PathTarget target) {
    Objects.requireNonNull(pattern, "pattern");
    Objects.requireNonNull(target, "target");
    PermissionPathPattern compiled =
        compiledPatterns.computeIfAbsent(pattern, PermissionPathPattern::of);
    return compiled.matches(target.relativePosixPath(), target.directory());
  }
}

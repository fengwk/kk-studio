package fun.fengwk.kkstudio.harness.runtime.thread;

import java.util.Objects;

/**
 * Thread 创建时冻结的执行约束，创建后不可变。
 *
 * <p>{@code maxOutputTokens} 为 null 表示不设输出上限；{@code promptCacheDisabled} 为 true 时该 Thread 的每次请求都不使用
 * provider prompt cache；{@code allowAutoCompaction} 为 false 时该 Thread 绝不自动规划压缩。
 *
 * <p>子 Thread 的有效约束是请求约束与父约束的合并（{@link #merge}），恒取更紧的一侧，因此约束沿执行树只会收紧、不会放宽。
 */
public record ThreadConstraints(
    Integer maxOutputTokens, boolean promptCacheDisabled, boolean allowAutoCompaction) {

  /** 用户创建的执行根默认约束：无输出上限、使用 cache、允许自动压缩。 */
  public static final ThreadConstraints NONE = new ThreadConstraints(null, false, true);

  public ThreadConstraints {
    if (maxOutputTokens != null && maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
  }

  /** 合并父约束，恒取更紧的值：输出上限取小，cache 禁用取或，自动压缩取与。 */
  public ThreadConstraints merge(ThreadConstraints parent) {
    Objects.requireNonNull(parent, "parent");
    Integer cap = maxOutputTokens;
    if (cap == null) {
      cap = parent.maxOutputTokens;
    } else if (parent.maxOutputTokens != null) {
      cap = Math.min(cap, parent.maxOutputTokens);
    }
    return new ThreadConstraints(
        cap,
        promptCacheDisabled || parent.promptCacheDisabled,
        allowAutoCompaction && parent.allowAutoCompaction);
  }
}

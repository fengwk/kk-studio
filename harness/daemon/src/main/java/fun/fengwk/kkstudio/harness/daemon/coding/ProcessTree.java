package fun.fengwk.kkstudio.harness.daemon.coding;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 子进程树的确定性终止：先终止全部后代，再终止主进程，短暂宽限后强制终止。
 *
 * <p>只依据 {@link ProcessHandle#descendants()} 与 {@link Process#destroy()}
 * 的原生语义，不引入任何进程管理框架。终止是幂等的：进程已退出时后续操作 只是空操作。
 */
final class ProcessTree {

  private static final Duration GRACE = Duration.ofMillis(200);

  private ProcessTree() {}

  /** 终止进程及其全部后代；{@code process} 为 null 或已退出时无副作用。 */
  static void terminate(Process process) {
    if (process == null) {
      return;
    }
    ProcessHandle handle;
    try {
      handle = process.toHandle();
    } catch (UnsupportedOperationException ignored) {
      process.destroyForcibly();
      return;
    }
    if (!handle.isAlive()) {
      return;
    }
    try {
      handle.descendants().forEach(ProcessHandle::destroyForcibly);
    } catch (RuntimeException ignored) {
      // 枚举失败不应阻止主进程终止。
    }
    process.destroy();
    try {
      if (!process.waitFor(GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  /** 进程树是否仍有存活成员（含主进程与后代）；用于终止后的确定性收敛断言。 */
  static boolean isAlive(Process process) {
    Objects.requireNonNull(process, "process");
    ProcessHandle handle = process.toHandle();
    if (handle.isAlive()) {
      return true;
    }
    try {
      return handle.descendants().anyMatch(ProcessHandle::isAlive);
    } catch (RuntimeException error) {
      return false;
    }
  }
}

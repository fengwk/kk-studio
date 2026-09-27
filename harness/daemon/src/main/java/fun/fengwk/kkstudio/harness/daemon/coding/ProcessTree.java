package fun.fengwk.kkstudio.harness.daemon.coding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 子进程树的确定性终止：先对整棵树温和停止，宽限窗口内仍未收敛的成员再强制终止。
 *
 * <p>只依据 {@link ProcessHandle#descendants()} 与 {@link Process#destroy()} / {@link
 * ProcessHandle#destroyForcibly()} 的原生语义，不引入任何进程管理框架。终止是幂等的：进程已退出时后续操作只是空操作。
 *
 * <p>成员在温和阶段之前一次性快照：主进程退出后其后代会被重新挂到 init（或 Windows 等价进程）之下，不再是主进程的
 * descendant，届时再枚举将漏掉必须收敛的成员。子进程先于父进程退出、或父进程在宽限期内自行退出，都不影响强制阶段继续覆盖已快照成员。
 */
final class ProcessTree {

  /** 温和停止后的宽限窗口：窗口内自行退出的成员不会进入强制阶段。 */
  private static final Duration GRACE = Duration.ofMillis(200);

  /** 收敛轮询步长：足够细，使正常退出的进程树不会白等整个宽限窗口。 */
  private static final long POLL_INTERVAL_MILLIS = 10;

  private ProcessTree() {}

  /**
   * 终止进程及其全部后代；{@code process} 为 null 或已退出时无副作用。
   *
   * <p>顺序固定为「温和停止整棵树 -> 有界等待 -> 强制终止仍存活的成员 -> 有界等待收敛」，因此支持优雅收尾的进程能先执行自己的清理逻辑， 而忽略温和信号的进程也不会永久存活。
   */
  static void terminate(Process process) {
    if (process == null) {
      return;
    }
    ProcessHandle root;
    try {
      root = process.toHandle();
    } catch (UnsupportedOperationException ignored) {
      process.destroyForcibly();
      return;
    }
    List<ProcessHandle> tree = snapshot(root);
    if (tree.isEmpty()) {
      return;
    }
    signal(tree, false);
    awaitConvergence(tree, GRACE);
    signal(tree, true);
    awaitConvergence(tree, GRACE);
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

  /** 主进程与全部后代的快照；主进程已退出时返回空列表（后代已不再可枚举）。 */
  private static List<ProcessHandle> snapshot(ProcessHandle root) {
    if (!root.isAlive()) {
      return List.of();
    }
    List<ProcessHandle> members = new ArrayList<>();
    members.add(root);
    try {
      root.descendants().forEach(members::add);
    } catch (RuntimeException ignored) {
      // 枚举失败不应阻止主进程终止：至少收敛已快照的成员。
    }
    return members;
  }

  /** 对全部快照成员发终止信号；单个成员失败不影响其它成员。 */
  private static void signal(List<ProcessHandle> tree, boolean forcibly) {
    for (ProcessHandle member : tree) {
      try {
        if (!member.isAlive()) {
          continue;
        }
        if (forcibly) {
          member.destroyForcibly();
        } else {
          member.destroy();
        }
      } catch (RuntimeException ignored) {
        // 进程已退出或平台不支持该操作：继续处理其它成员。
      }
    }
  }

  /** 有界等待整棵树收敛；中断时恢复中断标记并交回调用方按剩余状态决定后续动作。 */
  private static void awaitConvergence(List<ProcessHandle> tree, Duration budget) {
    long deadlineNanos = System.nanoTime() + budget.toNanos();
    while (anyAlive(tree) && System.nanoTime() < deadlineNanos) {
      try {
        TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static boolean anyAlive(List<ProcessHandle> tree) {
    for (ProcessHandle member : tree) {
      try {
        if (member.isAlive()) {
          return true;
        }
      } catch (RuntimeException ignored) {
        // 查询失败按不可判定处理：交给后续强制阶段兜底。
      }
    }
    return false;
  }
}

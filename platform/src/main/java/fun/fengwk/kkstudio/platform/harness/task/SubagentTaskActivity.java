package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.platform.harness.task.repo.SubagentTaskRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * "某个 Thread（含其委派子树）是否仍有未交付委派"的唯一权威聚合点。
 *
 * <p>异步委派把「父 Thread 自己的 turn 已结束」与「委派子树仍有工作」分开：父在等待子结果时自身处于 idle，但对外必须仍然呈现 processing，否则 Issue
 * 推进会误判静止、UI 会误判空闲。本类把该聚合收敛到一处，供结算扫描与对外聚合共用同一语义。
 *
 * <p>活动由两类持久事实构成，且两者的判定不同：
 *
 * <ul>
 *   <li>**正在执行（{@code OPEN}）**：子树内存在 {@code OPEN} 记录即构成活动，与祖先是否停止无关——运行中的执行是真实在跑的工作，
 *       停止尚未确认前不能当作已经安全停止，祖先（含传入 Thread 自身）的停止状态绝不能掩盖后代的运行。
 *   <li>**已终结待交付（{@code SETTLED}）**：只有其父 Thread 未停止时才构成活动。父已停止时该结果不会被交付（不唤醒父）， 它既不占用处理能力，也不应让
 *       owner/Issue 永远处于 processing。
 * </ul>
 *
 * <p>因此传入 Thread 自身停止并不使聚合直接返回 false：只要子树里还有 {@code OPEN} 执行，它依然是活动的。
 *
 * <p>这里是纯读取与聚合，不做任何状态推进。
 */
public class SubagentTaskActivity {

  private final SubagentTaskRepository repository;
  private final Supplier<HarnessRuntime> runtimeProvider;

  public SubagentTaskActivity(
      SubagentTaskRepository repository, Supplier<HarnessRuntime> runtimeProvider) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.runtimeProvider = Objects.requireNonNull(runtimeProvider, "runtimeProvider");
  }

  /**
   * 传入 Thread 自身或其委派子树是否仍构成活动：子树内还有 {@code OPEN} 执行，或还有父未停止的待交付终态。
   *
   * <p>{@code OPEN} 一律构成活动（即便传入 Thread 自身或其中间父已停止）；{@code SETTLED} 只在相应父未停止时构成活动，
   * 因此「已停止线程下的纯挂起结果」不会被算作活动、也不会让外部误判它需要被唤醒。
   */
  public boolean hasPendingDelegatedWork(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    HarnessRuntime runtime = runtimeProvider.get();
    if (runtime == null) {
      return false;
    }
    if (repository.hasOpenInSubtree(threadId)) {
      return true;
    }
    List<UUID> settledParents = repository.listSettledParentThreadIdsInSubtree(threadId);
    for (UUID parentThreadId : settledParents) {
      ThreadSnapshot parent = readThread(runtime, parentThreadId);
      if (parent != null && !SubagentTaskTerminalProjection.stopped(parent)) {
        return true;
      }
    }
    return false;
  }

  /** 读取 Thread 快照；Thread 不存在视为 null（调用方按"不再被唤醒"处理）。 */
  static ThreadSnapshot readThread(HarnessRuntime runtime, UUID threadId) {
    try {
      return runtime.getThreadSnapshot(threadId);
    } catch (HarnessRuntimeNotFoundException notFound) {
      return null;
    }
  }
}

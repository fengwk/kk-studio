package fun.fengwk.kkstudio.harness.spi.driver;

import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;

import java.util.List;
import java.util.UUID;

/**
 * Thread 深删时对 Driver 私有状态的两阶段协作端口。
 *
 * <p>锁序与删除顺序由 Runtime/Store 保持：先锁定 Thread 与 Command，再 {@link #lockForDeletion} 锁定 Driver 私有行并返回其
 * Work；随后锁定并删除 THREAD / Driver / TOOL Work，删除 ToolInvocation 与 Driver 私有行，最后删除 Thread。两阶段都在调用方
 * 已有事务内执行，不得开启新事务或执行外部 I/O。
 */
public interface DriverThreadDeletion {

  /** 无 Driver 私有状态的空实现；测试或纯核 Store 可直接使用。 */
  DriverThreadDeletion NONE =
      new DriverThreadDeletion() {
        @Override
        public List<WorkTarget> lockForDeletion(HarnessStore.Transaction tx, List<UUID> threadIds) {
          return List.of();
        }

        @Override
        public void deleteLocked(
            HarnessStore.Transaction tx,
            List<UUID> threadIds,
            List<WorkTarget> lockedWorkTargets) {}
      };

  /**
   * 按 Driver 自己的锁阶梯锁定待删除 Thread 的私有行，返回这些行持有的 Work target。
   *
   * @param threadIds 已按 UUID 升序、且 Thread 行已锁定的待删除集合
   */
  List<WorkTarget> lockForDeletion(HarnessStore.Transaction tx, List<UUID> threadIds);

  /**
   * 在 Work 与 ToolInvocation 已删除后删除已锁定的 Driver 私有行。
   *
   * @param threadIds 完整删除集合
   * @param lockedWorkTargets 第一阶段返回并已锁定/删除 Work 的 target
   */
  void deleteLocked(
      HarnessStore.Transaction tx, List<UUID> threadIds, List<WorkTarget> lockedWorkTargets);
}

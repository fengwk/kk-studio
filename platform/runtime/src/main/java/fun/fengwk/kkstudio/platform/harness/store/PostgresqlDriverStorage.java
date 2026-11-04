package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driver 私有行与通用 Thread/Work 的事务内协作。
 *
 * <p>删除时先按 Driver 锁阶梯锁定私有行，返回其 Work 身份；通用存储删除 Work 后再删除已锁定的私有行。
 */
public interface PostgresqlDriverStorage {

  List<WorkTarget> lockForThreads(List<UUID> threadIds);

  void deleteLocked(List<WorkTarget> targets);

  Optional<UUID> workOwner(UUID targetId);

  boolean workTargetExists(UUID targetId);
}

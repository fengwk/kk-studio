package fun.fengwk.kkstudio.platform.harness.store;

import java.util.Optional;
import java.util.UUID;

/**
 * Driver Work target 的通用存在性与属主查询。
 *
 * <p>Work 行本身由通用 Work SQL 管理；只有 target 所属 Thread 的解析依赖具体 Driver。Thread 深删的私有行清理不经过本接口， 而由 {@link
 * fun.fengwk.kkstudio.harness.spi.driver.DriverThreadDeletion} 生命周期完成。
 */
public interface PostgresqlDriverWorkStorage {

  Optional<UUID> workOwner(UUID targetId);

  boolean workTargetExists(UUID targetId);
}

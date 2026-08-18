package fun.fengwk.kkstudio.core.systemsettings;

import java.time.Instant;

/**
 * 单行 {@code system_setting}（{@code id=1}）的持久化契约。
 *
 * <p>配置聚合与行版本一起读写；CAS 更新由数据库 {@code where id = 1 and version = #{expectedVersion}} 原子保证。
 */
public interface SystemSettingsRepository {

  /** 当前单行聚合的完整读取投影。 */
  record SystemSettingsRecord(
      SystemSettings settings, long version, Instant createTime, Instant updateTime) {}

  /** 读取 id=1 行；行不存在（只可能由外部 DDL 造成）时返回 {@code null}。 */
  SystemSettingsRecord get();

  /** 以 {@code expectedVersion} CAS 整体替换 config 并 {@code version + 1}；成功返回 true，否则返回 false。 */
  boolean update(SystemSettings settings, long expectedVersion);
}

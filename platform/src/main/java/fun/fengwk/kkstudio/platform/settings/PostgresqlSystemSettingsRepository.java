package fun.fengwk.kkstudio.platform.settings;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 基于 PostgreSQL 的单行 {@code system_setting} 仓库。
 *
 * <p>CAS 写路径要求调用方已开启事务：在执行 SQL 前先校验事务，未开启事务在写行之前直接拒绝；成功更新与 {@code system_settings_changed} 通知共用同一事务
 * Connection，提交后唤醒其它节点回读权威 version，影响 0 行的陈旧 CAS 静默。
 */
@AllArgsConstructor
@Repository
public class PostgresqlSystemSettingsRepository implements SystemSettingsRepository {

  private final SystemSettingsMapper systemSettingsMapper;
  private final SystemSettingsCodec systemSettingsCodec;
  private final SystemSettingsChangeNotifier notifier;

  @Override
  public SystemSettingsRecord get() {
    return convert(systemSettingsMapper.get());
  }

  @Override
  public boolean update(SystemSettings settings, long expectedVersion) {
    SystemSettingsChangeNotifier.requireTransaction();
    if (systemSettingsMapper.updateByVersion(systemSettingsCodec.encode(settings), expectedVersion)
        != 1) {
      return false;
    }
    // WHERE version = expectedVersion 命中即 version = version + 1，payload 是写后权威 version。
    notifier.versionChanged(expectedVersion + 1);
    return true;
  }

  private SystemSettingsRecord convert(SystemSettingsDO row) {
    if (row == null) {
      return null;
    }
    return new SystemSettingsRecord(
        systemSettingsCodec.decode(row.getConfigJson()),
        row.getVersion(),
        row.getCreateTime(),
        row.getUpdateTime());
  }
}

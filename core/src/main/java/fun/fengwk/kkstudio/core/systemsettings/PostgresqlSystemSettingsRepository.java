package fun.fengwk.kkstudio.core.systemsettings;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/** 基于 PostgreSQL 的单行 {@code system_setting} 仓库。 */
@AllArgsConstructor
@Repository
public class PostgresqlSystemSettingsRepository implements SystemSettingsRepository {

  private final SystemSettingsMapper systemSettingsMapper;
  private final SystemSettingsCodec systemSettingsCodec;

  @Override
  public SystemSettingsRecord get() {
    return convert(systemSettingsMapper.get());
  }

  @Override
  public boolean update(SystemSettings settings, long expectedVersion) {
    return systemSettingsMapper.updateByVersion(
            systemSettingsCodec.encode(settings), expectedVersion)
        == 1;
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

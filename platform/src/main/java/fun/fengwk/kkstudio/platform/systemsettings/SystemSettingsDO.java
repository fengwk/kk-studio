package fun.fengwk.kkstudio.platform.systemsettings;

import lombok.Data;

import java.time.Instant;

/** {@code system_setting} 行映射：唯一 id=1 的全局系统设置聚合。 */
@Data
public class SystemSettingsDO {

  /** 恒为 1（schema check {@code ck_system_setting_id}）。 */
  private Long id;

  /** 完整强类型聚合的 canonical JSON（{@code config} jsonb 列，读为字符串、写回时 cast jsonb）。 */
  private String configJson;

  /** 乐观锁行版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，应用侧维护，毫秒精度）。 */
  private Instant updateTime;
}

package fun.fengwk.kkstudio.share.systemsettings;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/** GET {@code /api/settings} 的完整聚合表示：六个 section + 当前乐观锁版本与时戳。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SystemSettingsDTO extends SystemSettingsSectionsDTO {

  /** 非负十进制字符串版本号；客户端每次 PUT 时必须回传为 {@code expectedVersion}。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant）。 */
  private Instant updateTime;
}

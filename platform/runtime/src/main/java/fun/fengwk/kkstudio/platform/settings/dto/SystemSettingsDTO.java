package fun.fengwk.kkstudio.platform.settings.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/** {@code GET /api/platform/settings} 的完整聚合表示：六个 section + 当前乐观锁版本与时戳。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SystemSettingsDTO extends SystemSettingsSectionsDTO {

  /** 非负十进制字符串版本号；客户端每次 PUT 时必须回传为 {@code expectedVersion}。 */
  private String version;

  /** 创建时刻。 */
  private Instant createdAt;

  /** 最近更新时刻。 */
  private Instant updatedAt;
}

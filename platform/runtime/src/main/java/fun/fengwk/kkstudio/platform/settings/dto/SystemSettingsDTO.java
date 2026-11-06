package fun.fengwk.kkstudio.platform.settings.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/** {@code GET /api/platform/settings} 的完整聚合表示：六个 section + 当前乐观锁版本与时戳。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SystemSettingsDTO extends SystemSettingsSectionsDTO {

  /** 乐观锁版本；wire 上为非负十进制字符串，客户端每次 PUT 时必须回传为 {@code expectedVersion}。 */
  private Long version;

  /** 创建时刻。 */
  private Instant createdAt;

  /** 最近更新时刻。 */
  private Instant updatedAt;
}

package fun.fengwk.kkstudio.share.systemsettings;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** PUT {@code /api/settings} 请求体：完整替换聚合的六个 section + 必填 {@code expectedVersion} CAS 令牌。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SystemSettingsUpdateDTO extends SystemSettingsSectionsDTO {

  /** 必填非负十进制字符串；必须与当前聚合版本一致。 */
  private String expectedVersion;
}

package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 发布暂存的上传归档；{@code expectedVersion} 为 null 表示新建上传渠道包。 */
@Data
public class SkillPackageStagingPublishDTO {

  /** 目标包名。 */
  private String packageName;

  /** 用户填写的版本号。 */
  private String packageVersion;

  /** 可空包描述。 */
  private @Nullable String description;

  /** 客户端已展示的 CAS 版本号；新建时为 null。 */
  private @Nullable Long expectedVersion;
}

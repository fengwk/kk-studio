package fun.fengwk.kkstudio.share.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 发布预览暂存的上传归档；{@code expectedVersion} 为 null 表示新建上传渠道包。 */
@Data
public class SkillPackageUploadPublishDTO {

  /** 预览返回的暂存上传 id。 */
  private String stagingUploadId;

  /** 目标包名。 */
  private String packageName;

  /** 用户填写的版本号。 */
  private String packageVersion;

  /** 可空包描述。 */
  private @Nullable String description;

  /** 客户端已展示的 CAS 版本号；新建时为 null。 */
  private @Nullable String expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package field: " + fieldName);
  }
}

package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** 用已完成的原始上传构建技能包暂存；服务端读取原始压缩包并走统一构建器。 */
@Data
public class SkillPackageStagingCreateDTO {

  /** 已 complete 的原始压缩包上传 id（zip 或 tar.gz）。 */
  private UUID uploadId;

  /** 目标包名。 */
  private String packageName;

  /** 用户填写的版本号（1–128 字符）。 */
  private String packageVersion;

  /** 可空包描述。 */
  private @Nullable String description;
}

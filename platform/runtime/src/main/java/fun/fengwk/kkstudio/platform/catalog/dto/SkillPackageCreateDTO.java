package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

/** 新建 Git 渠道技能包：创建即发布当时解析出的 branch HEAD。 */
@Data
public class SkillPackageCreateDTO {

  /** 包名（1–64 个小写字母或数字，以单个连字符分隔）。 */
  private String packageName;

  /** 可空包描述（去边缘空白后 1–1024 字符）。 */
  private @Nullable String description;

  /** 不可变 Git repository URL。 */
  private String repositoryUrl;

  /** 只用于检查候选更新的 branch。 */
  private String branch;

  /** 私有仓库访问令牌：省略表示无令牌，只接受 SET；绝不回显。 */
  private @Nullable SecretUpdate token;
}

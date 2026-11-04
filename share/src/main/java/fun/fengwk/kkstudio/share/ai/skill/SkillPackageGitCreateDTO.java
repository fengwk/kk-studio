package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/** 新建 Git 渠道技能包：创建即发布当时解析出的 branch HEAD。 */
@Data
public class SkillPackageGitCreateDTO {

  /** 包名（1–64 个小写字母或数字，以单个连字符分隔）。 */
  private String packageName;

  /** 可空包描述（去边缘空白后 1–1024 字符）。 */
  private @Nullable String description;

  /** 不可变 Git repository URL。 */
  private String repositoryUrl;

  /** 只用于检查候选更新的 branch。 */
  private String branch;

  /** 可空私有仓库访问令牌；绝不回显。 */
  @ToString.Exclude private @Nullable String token;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package field: " + fieldName);
  }
}

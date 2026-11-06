package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;

import fun.fengwk.kkstudio.platform.secret.SecretConfiguredDTO;

/** Git 渠道技能包的仓库事实投影：令牌只以已配置事实表达，绝不回显。 */
@Data
public class SkillGitSourceDTO {

  /** 不可变 Git repository URL。 */
  private String repositoryUrl;

  /** 只用于检查候选更新的 branch。 */
  private String branch;

  /** 是否配置了私有仓库访问令牌。 */
  private SecretConfiguredDTO token;
}

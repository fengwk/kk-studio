package fun.fengwk.kkstudio.share.ai.skill;

import lombok.Data;

/** Git 渠道技能包的仓库事实投影：令牌只以 {@code hasToken} 表达，绝不回显。 */
@Data
public class SkillGitSourceDTO {

  /** 不可变 Git repository URL。 */
  private String repositoryUrl;

  /** 只用于检查候选更新的 branch。 */
  private String branch;

  /** 是否配置了私有仓库访问令牌。 */
  private boolean hasToken;
}

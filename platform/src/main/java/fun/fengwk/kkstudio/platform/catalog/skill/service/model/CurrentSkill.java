package fun.fengwk.kkstudio.platform.catalog.skill.service.model;

import lombok.Data;

/**
 * 当前生效的全局 Skill 目录行（{@code skill}）与被指向的不可变 {@code skill_revision} 事实的联合视图。
 *
 * <p>目录行只持有身份；描述、正文与 revision 都来自不可变的 revision 行，因此“当前版本”与“冻结版本”是同一份事实：package 切换后旧 请求仍会按自己冻结的
 * revision 精确取回内容。
 */
@Data
public class CurrentSkill {

  /** Skill canonical 名（全局唯一）。 */
  private String name;

  /** 承载该 Skill 的 package 名。 */
  private String packageName;

  /** 承载该 Skill 的不可变 package 版本。 */
  private String packageVersion;

  /** 该 Skill 的描述（来自不可变 revision 行）。 */
  private String description;

  /** 该 Skill 的正文 revision（来自不可变 revision 行）。 */
  private String contentRevision;

  /** 该 Skill 正文（来自不可变 revision 行）。 */
  private String content;
}

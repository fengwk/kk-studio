package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/**
 * Agent 当前配置中的一个 Skill 引用身份：{@code (packageName, name)}。
 *
 * <p>只陈述配置声明本身，不含 Skill 描述、正文或交付路径；本次请求真实交付的 Skill 事实由 Debug 顶层 {@code skills} 投影承载。
 */
@Data
public class AgentSkillReferenceDTO {

  /** Skill 所属 Package 名。 */
  private String packageName;

  /** Package 内的 Skill 名。 */
  private String name;
}

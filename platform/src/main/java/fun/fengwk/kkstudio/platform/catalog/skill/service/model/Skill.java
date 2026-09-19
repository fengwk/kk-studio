package fun.fengwk.kkstudio.platform.catalog.skill.service.model;

import lombok.Data;

import java.time.Instant;

/**
 * {@code skill} 行的领域模型：一个 Skill 的精确内容事实。
 *
 * <p>身份是 {@code (packageName, packageVersion, name)}，内容由 {@code description} 与 {@code content}
 * 精确决定；历史版本行永不修改、永不删除，只有 {@code active} 标记随 package 替换或删除变化，因此任何已冻结的三元组都能永久精确取回正文。
 */
@Data
public class Skill {

  /** 所属 package 名。 */
  private String packageName;

  /** 所属 package 版本。 */
  private String packageVersion;

  /** Skill canonical 名（该 package 版本内唯一）。 */
  private String name;

  /** Skill 描述（非空、无环绕空白、≤1024）。 */
  private String description;

  /** Skill 完整正文（非空，长度不设人为上限）。 */
  private String content;

  /** 是否为该 Skill 名当前生效的行。 */
  private boolean active;

  /** 创建时间（毫秒精度）。 */
  private Instant createTime;
}

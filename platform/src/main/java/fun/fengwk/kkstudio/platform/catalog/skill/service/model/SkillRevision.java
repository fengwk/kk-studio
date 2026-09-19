package fun.fengwk.kkstudio.platform.catalog.skill.service.model;

import lombok.Data;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;

import java.time.Instant;

/**
 * {@code skill_revision} 行的领域模型：一个不可变的 Skill 内容版本。
 *
 * <p>行一经写入永不修改、永不删除；{@code contentRevision} 是 {@code content} 精确 UTF-8 字节的 SHA-256，因此 {@code
 * (packageName, packageVersion, name, contentRevision)} 能唯一精确取回正文。
 */
@Data
public class SkillRevision {

  /** 所属 package 名。 */
  private String packageName;

  /** 所属不可变 package 版本。 */
  private String packageVersion;

  /** Skill canonical 名（该 package 版本内唯一）。 */
  private String name;

  /** Skill 描述（非空、无环绕空白、≤1024）。 */
  private String description;

  /** Skill 完整正文（非空，长度不设人为上限）。 */
  private String content;

  /** 正文精确 UTF-8 字节的 SHA-256（小写 64 位十六进制）。 */
  private String contentRevision;

  /** 创建时间（毫秒精度）。 */
  private Instant createTime;

  /** 校验并设置 Skill 名。 */
  public void setName(String value) {
    this.name = SkillNames.canonicalSkillName(value);
  }

  /** 校验并设置 Skill 描述。 */
  public void setDescription(String value) {
    this.description = SkillNames.canonicalDescription(value);
  }

  /** 校验并设置正文。 */
  public void setContent(String value) {
    this.content = SkillNames.canonicalContent(value);
  }

  /** 校验并设置 content revision。 */
  public void setContentRevision(String value) {
    this.contentRevision = SkillNames.contentRevision(value);
  }
}

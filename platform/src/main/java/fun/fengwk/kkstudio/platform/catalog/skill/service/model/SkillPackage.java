package fun.fengwk.kkstudio.platform.catalog.skill.service.model;

import lombok.Data;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;

import java.time.Instant;

/**
 * {@code skill_package} 行的领域模型：一个不可变的 package 版本。
 *
 * <p>{@code packageRevision} 是该版本内容的确定性聚合 revision；{@code active} 表示它是否是同名 package 当前的活跃版本。
 * 非活跃的旧版本永久保留，因此任何历史冻结 revision 都能被精确取回。
 */
@Data
public class SkillPackage {

  /** package 名（主键第一部分，映射 {@code skill_package.package_name}）。 */
  private String packageName;

  /** package 版本（主键第二部分，映射 {@code skill_package.package_version}）；一经写入永不复用。 */
  private String packageVersion;

  /** 可空 package 描述；null 表示未填写。 */
  private String description;

  /** 该版本内容的确定性聚合 SHA-256（小写 64 位十六进制）。 */
  private String packageRevision;

  /** 是否为该 package 名当前的活跃版本。 */
  private boolean active;

  /** 创建时间（毫秒精度）。 */
  private Instant createTime;

  /** 校验并设置 package 名（canonical 规则与全局 Skill 名一致）。 */
  public void setPackageName(String value) {
    this.packageName = SkillNames.canonicalPackageName(value);
  }

  /** 校验并设置 package 版本。 */
  public void setPackageVersion(String value) {
    this.packageVersion = SkillNames.canonicalPackageVersion(value);
  }

  /** 校验并设置聚合 revision。 */
  public void setPackageRevision(String value) {
    this.packageRevision = SkillNames.contentRevision(value);
  }
}

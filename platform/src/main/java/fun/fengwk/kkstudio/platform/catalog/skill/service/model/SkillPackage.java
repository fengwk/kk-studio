package fun.fengwk.kkstudio.platform.catalog.skill.service.model;

import lombok.Data;

import java.time.Instant;

/**
 * {@code skill_package} 行的领域模型：一个 package 版本。
 *
 * <p>版本行一经写入永不修改、永不删除，唯一可变的事实是 {@code active}：同名 package 至多一个活跃版本，删除只把当前版本置为非活跃。
 * 更新是一次完整替换，安装一个从未使用过的新 {@code packageVersion}。
 */
@Data
public class SkillPackage {

  /** package 名（主键第一部分，映射 {@code skill_package.package_name}）。 */
  private String packageName;

  /** package 版本（主键第二部分，映射 {@code skill_package.package_version}）；一经写入永不复用。 */
  private String packageVersion;

  /** 可空 package 描述；null 表示未填写。 */
  private String description;

  /** 是否为该 package 名当前的活跃版本。 */
  private boolean active;

  /** 创建时间（毫秒精度）。 */
  private Instant createTime;
}

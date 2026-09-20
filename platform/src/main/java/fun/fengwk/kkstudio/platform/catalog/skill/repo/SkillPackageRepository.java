package fun.fengwk.kkstudio.platform.catalog.skill.repo;

import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;

import java.util.List;

/**
 * Platform 全局 Skill Package 权威行的仓库边界。
 *
 * <p>每个 {@code packageName} 恰一行；写路径要求调用方已开启事务，并以 {@link #lockPackage(String)} 取行锁后执行 CAS。读路径不取锁。
 */
public interface SkillPackageRepository {

  /** 按 {@code package_name asc} 列出全部 Package。 */
  List<SkillPackage> listPackages();

  /** 读取某 Package；不存在返回 null。 */
  SkillPackage getPackage(String packageName);

  /** 以 {@code FOR UPDATE} 读取某 Package；不存在返回 null。 */
  SkillPackage lockPackage(String packageName);

  /** 插入一行 Package；主键冲突返回 false。 */
  boolean insertPackage(SkillPackage skillPackage);

  /** 以 {@code expectedVersion} 为条件整体更新 Package 的可变事实；影响行数为 0 表示并发冲突。 */
  boolean updatePackage(SkillPackage skillPackage, long expectedVersion);

  /** 以 {@code expectedVersion} 为条件删除 Package；影响行数为 0 表示不存在或并发冲突。 */
  boolean deletePackage(String packageName, long expectedVersion);
}

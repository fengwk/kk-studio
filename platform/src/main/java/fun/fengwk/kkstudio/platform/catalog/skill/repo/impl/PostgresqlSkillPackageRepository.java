package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.mapper.SkillPackageMapper;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillPackageDO;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;

import java.util.List;
import java.util.Objects;

/**
 * 基于 PostgreSQL 的 Platform 全局 Skill Package 权威行仓库。
 *
 * <p>写路径要求调用方已开启事务：成功写与 {@code skill_package_changed} 通知共用同一事务 Connection，因此只有真正提交的 insert/delete
 * 或推进 version 的 update 才会在提交后投递；CAS 影响 0 行与未开启事务都静默/拒绝。
 */
@AllArgsConstructor
@Repository
public class PostgresqlSkillPackageRepository implements SkillPackageRepository {

  private final SkillPackageMapper skillPackageMapper;
  private final PostgresqlSkillPackageChangeNotifier notifier;

  @Override
  public List<SkillPackage> listPackages() {
    return skillPackageMapper.listPackages().stream().map(this::toPackage).toList();
  }

  @Override
  public SkillPackage getPackage(String packageName) {
    return toPackage(skillPackageMapper.getPackage(packageName));
  }

  @Override
  public SkillPackage lockPackageForShare(String packageName) {
    return toPackage(skillPackageMapper.lockPackageForShare(packageName));
  }

  @Override
  public SkillPackage lockPackage(String packageName) {
    return toPackage(skillPackageMapper.lockPackage(packageName));
  }

  @Override
  public boolean insertPackage(SkillPackage skillPackage) {
    if (skillPackageMapper.insertPackage(toPackageDO(skillPackage)) != 1) {
      return false;
    }
    notifier.packageChanged(skillPackage.getPackageName());
    return true;
  }

  @Override
  public boolean updatePackage(SkillPackage skillPackage, long expectedVersion) {
    if (skillPackageMapper.updatePackage(toPackageDO(skillPackage), expectedVersion) != 1) {
      return false;
    }
    // UPDATE 命中即 version = version + 1，因此成功写的 version 必然真变化，与通知条件一致。
    notifier.packageChanged(skillPackage.getPackageName());
    return true;
  }

  @Override
  public boolean deletePackage(String packageName, long expectedVersion) {
    if (skillPackageMapper.deletePackage(packageName, expectedVersion) != 1) {
      return false;
    }
    notifier.packageChanged(packageName);
    return true;
  }

  private SkillPackage toPackage(SkillPackageDO row) {
    if (row == null) {
      return null;
    }
    SkillPackage target = new SkillPackage();
    target.setPackageName(row.getPackageName());
    target.setDescription(row.getDescription());
    target.setRepositoryUrl(row.getRepositoryUrl());
    target.setBranch(row.getBranch());
    target.setCurrentCommit(row.getCurrentCommit());
    target.setObservedHeadCommit(row.getObservedHeadCommit());
    target.setHeadCheckedAt(row.getHeadCheckedAt());
    target.setHeadCheckError(row.getHeadCheckError());
    target.setSkills(SkillManifestJson.decode(row.getSkillsJson()));
    target.setVersion(row.getVersion() == null ? 0L : row.getVersion());
    target.setCreateTime(row.getCreateTime());
    target.setUpdateTime(row.getUpdateTime());
    return target;
  }

  private SkillPackageDO toPackageDO(SkillPackage model) {
    SkillPackageDO target = new SkillPackageDO();
    target.setPackageName(model.getPackageName());
    target.setDescription(model.getDescription());
    target.setRepositoryUrl(model.getRepositoryUrl());
    target.setBranch(model.getBranch());
    target.setCurrentCommit(model.getCurrentCommit());
    target.setObservedHeadCommit(model.getObservedHeadCommit());
    target.setHeadCheckedAt(model.getHeadCheckedAt());
    target.setHeadCheckError(model.getHeadCheckError());
    target.setSkillsJson(SkillManifestJson.encode(requireSkills(model)));
    target.setVersion(model.getVersion());
    return target;
  }

  private static List<SkillManifestEntry> requireSkills(SkillPackage model) {
    return Objects.requireNonNull(model.getSkills(), "skills");
  }
}

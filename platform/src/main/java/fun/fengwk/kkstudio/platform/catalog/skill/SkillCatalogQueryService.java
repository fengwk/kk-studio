package fun.fengwk.kkstudio.platform.catalog.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;

import java.util.List;
import java.util.Objects;

/**
 * Platform 全局 Skill Package 的只读查询入口。
 *
 * <p>运行时的 Skill 解析、Prompt 渲染与稳定 URI 内容读取都只读取当前发布事实，因此共用这一个窄查询面。写路径一律走 {@link
 * fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService}。
 */
@Component
public class SkillCatalogQueryService {

  private final SkillPackageRepository repository;
  private final SkillTokenCipher tokenCipher;

  public SkillCatalogQueryService(SkillPackageRepository repository, SkillTokenCipher tokenCipher) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.tokenCipher = Objects.requireNonNull(tokenCipher, "tokenCipher");
  }

  /** 按 {@code package_name asc} 列出全部 Package。 */
  public List<SkillPackage> listPackages() {
    return repository.listPackages();
  }

  /** 读取某 Package；不存在返回 null。 */
  public SkillPackage getPackage(String packageName) {
    if (packageName == null) {
      return null;
    }
    return repository.getPackage(packageName);
  }

  /**
   * 以 {@code FOR SHARE} 读取某 Package；不存在返回 null。
   *
   * <p>调用方必须已开启事务，并在任何 Agent 行锁之前按 package name 升序调用。
   */
  public SkillPackage lockPackageForShare(String packageName) {
    if (packageName == null) {
      return null;
    }
    return repository.lockPackageForShare(packageName);
  }

  /**
   * 解析 Package 已配置的私有仓库访问令牌明文，供 Git 鉴权与受信任通道使用；未配置时返回 null。
   *
   * <p>这不是对外投影的一部分：REST 读取只暴露 {@code hasToken}。明文只在内存中短暂存在，调用方不得写入日志、Prompt 或模型上下文。
   */
  public String resolveAccessToken(SkillPackage skillPackage) {
    if (skillPackage == null || skillPackage.getEncryptedToken() == null) {
      return null;
    }
    return tokenCipher.decrypt(skillPackage.getPackageName(), skillPackage.getEncryptedToken());
  }
}

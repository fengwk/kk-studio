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

  public SkillCatalogQueryService(SkillPackageRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
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
}

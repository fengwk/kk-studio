package fun.fengwk.kkstudio.platform.catalog.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillCatalogRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.Skill;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Platform 全局 Skill 目录的只读查询入口。
 *
 * <p>运行时的 turn 规划与系统提示词预览都只读取当前活跃目录事实，因此共用这一个窄查询面：按 name 稳定排序的活跃 Skill 列表与活跃 package 列表。写路径一律走
 * {@link fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService}。
 */
@Component
public class SkillCatalogQueryService {

  private final SkillCatalogRepository repository;

  public SkillCatalogQueryService(SkillCatalogRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /** 按 {@code name} 升序列出全部活跃 Skill（含正文）。 */
  public List<Skill> listActiveSkills() {
    return repository.listActiveSkills();
  }

  /** 按 {@code name} 升序返回全部活跃 Skill 的索引，便于按选中名精确解析。 */
  public Map<String, Skill> activeSkillsByName() {
    Map<String, Skill> index = new LinkedHashMap<>();
    for (Skill skill : repository.listActiveSkills()) {
      index.put(skill.getName(), skill);
    }
    return index;
  }

  /** 按 {@code package_name asc, package_version asc} 列出全部活跃 package。 */
  public List<SkillPackage> listActivePackages() {
    return repository.listActivePackages();
  }

  /** 读取某 package 名当前的活跃版本；不存在返回 null。 */
  public SkillPackage getActivePackage(String packageName) {
    return repository.getActivePackage(packageName);
  }
}

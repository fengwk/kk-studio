package fun.fengwk.kkstudio.platform.catalog.skill.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.catalog.skill.git.JGitSkillRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitRepository;

/** Platform 侧 Git Skill 访问的组合根：没有本地缓存目录，每次发布使用一次性临时仓库。 */
@Configuration(proxyBeanMethods = false)
public class SkillCatalogConfiguration {

  @Bean
  public SkillGitRepository skillGitRepository() {
    return new JGitSkillRepository();
  }
}

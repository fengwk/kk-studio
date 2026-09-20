package fun.fengwk.kkstudio.platform.catalog.skill.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.catalog.skill.git.JGitSkillCache;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessRuntimeProperties;

/**
 * Platform 侧 Git Skill cache 的组合根：bare repository 根目录来自 {@code
 * kk-studio.harness.runtime.skill-cache-root}（缺省 {@code <cwd>/.kkstudio/skills}）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class SkillCatalogConfiguration {

  @Bean
  public SkillGitCache skillGitCache(HarnessRuntimeProperties properties) {
    return new JGitSkillCache(properties.resolvedSkillCacheRoot());
  }
}

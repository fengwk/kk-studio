package fun.fengwk.kkstudio.platform.catalog.skill.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** Platform Git Skill cache 的部署配置；每个 package 的 bare repository 位于根目录下。 */
@Data
@ConfigurationProperties(prefix = "kk-studio.catalog.skill")
public class SkillCatalogProperties {

  /** 未配置时取进程当前目录下的 {@code .kkstudio/skills}。 */
  private Path cacheRoot = Path.of(System.getProperty("user.dir", "."), ".kkstudio", "skills");

  public Path resolvedCacheRoot() {
    if (cacheRoot == null) {
      throw new IllegalArgumentException("kk-studio.catalog.skill.cache-root must not be null");
    }
    return cacheRoot.toAbsolutePath().normalize();
  }
}

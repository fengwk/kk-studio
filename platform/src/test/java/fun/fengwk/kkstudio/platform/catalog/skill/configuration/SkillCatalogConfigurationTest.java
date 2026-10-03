package fun.fengwk.kkstudio.platform.catalog.skill.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.platform.catalog.skill.git.JGitSkillCache;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;

import java.nio.file.Files;
import java.nio.file.Path;

class SkillCatalogConfigurationTest {

  @Test
  void bindsCatalogCacheRootAndCreatesOneCache(@TempDir Path tempDir) {
    // 意图：新部署 key 必须绑定到 catalog 并驱动实际 cache 根目录，而非仅绑定一个未被消费的字段。
    Path cacheRoot = tempDir.resolve("cache");
    new ApplicationContextRunner()
        .withBean("systemProxySelector", Object.class, Object::new)
        .withUserConfiguration(SkillCatalogConfiguration.class)
        .withPropertyValues("kk-studio.catalog.skill.cache-root=" + cacheRoot)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(SkillGitCache.class);
              assertThat(context.getBean(SkillGitCache.class)).isInstanceOf(JGitSkillCache.class);
              assertEquals(
                  cacheRoot, context.getBean(SkillCatalogProperties.class).resolvedCacheRoot());
              assertThat(Files.isDirectory(cacheRoot)).isTrue();
            });
  }

  @Test
  void retainsDefaultRootAndNormalizesConfiguredRoot() {
    // 意图：归属迁移不改变缺省路径及相对路径的解析语义。
    SkillCatalogProperties properties = new SkillCatalogProperties();
    assertEquals(
        Path.of(System.getProperty("user.dir", "."), ".kkstudio", "skills")
            .toAbsolutePath()
            .normalize(),
        properties.resolvedCacheRoot());
    properties.setCacheRoot(Path.of("cache", "..", "skills"));
    assertEquals(Path.of("skills").toAbsolutePath().normalize(), properties.resolvedCacheRoot());
  }

  @Test
  void rejectsNullRoot() {
    // 意图：显式空配置仍 fail closed，诊断必须指向新的部署 key。
    SkillCatalogProperties properties = new SkillCatalogProperties();
    properties.setCacheRoot(null);
    assertEquals(
        "kk-studio.catalog.skill.cache-root must not be null",
        assertThrows(IllegalArgumentException.class, properties::resolvedCacheRoot).getMessage());
  }
}

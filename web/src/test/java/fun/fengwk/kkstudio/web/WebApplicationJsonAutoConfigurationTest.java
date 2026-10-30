package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationImportSelector;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Arrays;
import java.util.List;

/**
 * 生产组合根 {@link WebApplication} 的自动装配架构验收。
 *
 * <p>Spring Boot 真实导入清单包含 Jackson 3 自动装配，且不含任何旧框架自动装配构件。
 */
class WebApplicationJsonAutoConfigurationTest {

  private static final String BOOT_JACKSON_AUTO_CONFIGURATION =
      JacksonAutoConfiguration.class.getName();

  private static final String RETIRED_FRAMEWORK_PREFIX =
      String.join(".", "fun", "fengwk", "convention4j") + ".";

  @Test
  void productionRootHasNoLegacyExcludes() {
    SpringBootApplication annotation =
        WebApplication.class.getAnnotation(SpringBootApplication.class);
    assertTrue(
        annotation.exclude().length == 0, "WebApplication should not declare legacy excludes");
  }

  @Test
  void effectiveImportsIncludeBootJacksonAndExcludeLegacyFramework() {
    List<String> effective = effectiveImports(WebApplication.class);
    assertTrue(
        effective.contains(BOOT_JACKSON_AUTO_CONFIGURATION),
        "Boot Jackson auto-configuration must be imported");
    assertFalse(
        effective.stream().anyMatch(name -> name.startsWith(RETIRED_FRAMEWORK_PREFIX)),
        "retired framework auto-configurations must not be imported");
  }

  /** 用真实导入选择器计算给定组合根的生效自动装配清单。 */
  private static List<String> effectiveImports(Class<?> source) {
    AutoConfigurationImportSelector selector = new AutoConfigurationImportSelector();
    selector.setBeanClassLoader(WebApplicationJsonAutoConfigurationTest.class.getClassLoader());
    selector.setResourceLoader(new DefaultResourceLoader());
    selector.setEnvironment(new StandardEnvironment());
    selector.setBeanFactory(new DefaultListableBeanFactory());
    return Arrays.asList(selector.selectImports(AnnotationMetadata.introspect(source)));
  }
}

package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.springboot.starter.i18n.I18nAutoConfiguration;
import fun.fengwk.convention4j.springboot.starter.json.JacksonAutoConfiguration;
import fun.fengwk.convention4j.springboot.starter.result.ResultAutoConfiguration;
import fun.fengwk.convention4j.springboot.starter.web.result.WebExceptionResultHandlerAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationImportSelector;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Arrays;
import java.util.List;

/**
 * 生产组合根 {@link WebApplication} 的 Web 自动装配排除验收。
 *
 * <p>convention4j 的 {@link JacksonAutoConfiguration} 只提供一个 Jackson 2 {@code ObjectMapper}
 * bean，且已无自有消费者；Result 自动包装与异常 Result 处理也由本仓显式响应信封替代。 WebApplication 显式排除它们，HTTP wire 只由 Boot4 装配的
 * Jackson 3 mapper 与自有 {@code ApiResponse} advice 承担。
 *
 * <p>用 Spring Boot 真实 {@link AutoConfigurationImportSelector} 读取生效清单：未声明排除时这些配置是真实候选，声明排除后不再被
 * 导入，同时其余 convention4j i18n 能力保留。
 */
class WebApplicationJsonAutoConfigurationExclusionTest {

  @Test
  void productionRootExcludesConventionWebResponseAutoConfigurations() {
    List<Class<?>> excluded =
        Arrays.asList(WebApplication.class.getAnnotation(SpringBootApplication.class).exclude());
    assertTrue(
        excluded.contains(JacksonAutoConfiguration.class),
        "WebApplication must exclude the convention4j Jackson 2 JSON auto-configuration");
    assertTrue(
        excluded.contains(ResultAutoConfiguration.class),
        "WebApplication must exclude the convention4j Result auto-configuration");
    assertTrue(
        excluded.contains(WebExceptionResultHandlerAutoConfiguration.class),
        "WebApplication must exclude convention4j Result exception handlers");
  }

  @Test
  void excludedConventionJacksonConfigurationIsDroppedFromEffectiveImports() {
    // 未声明排除时它确实在候选清单里，因此排除是真正的生效原因，而不是该配置根本不存在。
    assertTrue(
        effectiveImports(UndecoratedAutoConfiguration.class)
            .contains(JacksonAutoConfiguration.class.getName()),
        "convention4j Jackson 2 auto-configuration must be a real classpath candidate");
    assertTrue(
        effectiveImports(UndecoratedAutoConfiguration.class)
            .contains(ResultAutoConfiguration.class.getName()),
        "convention4j Result auto-configuration must be a real classpath candidate");
    assertTrue(
        effectiveImports(UndecoratedAutoConfiguration.class)
            .contains(WebExceptionResultHandlerAutoConfiguration.class.getName()),
        "convention4j web Result handler auto-configuration must be a real classpath candidate");

    List<String> effective = effectiveImports(WebApplication.class);
    assertFalse(
        effective.contains(JacksonAutoConfiguration.class.getName()),
        "excluded Jackson 2 auto-configuration must not be imported");
    assertFalse(
        effective.contains(ResultAutoConfiguration.class.getName()),
        "excluded Result auto-configuration must not be imported");
    assertFalse(
        effective.contains(WebExceptionResultHandlerAutoConfiguration.class.getName()),
        "excluded web Result handler auto-configuration must not be imported");
    assertTrue(
        effective.contains(I18nAutoConfiguration.class.getName()),
        "convention4j i18n capability must be retained");
  }

  /** 用真实导入选择器计算给定组合根的生效自动装配清单。 */
  private static List<String> effectiveImports(Class<?> source) {
    AutoConfigurationImportSelector selector = new AutoConfigurationImportSelector();
    selector.setBeanClassLoader(
        WebApplicationJsonAutoConfigurationExclusionTest.class.getClassLoader());
    selector.setResourceLoader(new DefaultResourceLoader());
    selector.setEnvironment(new StandardEnvironment());
    selector.setBeanFactory(new DefaultListableBeanFactory());
    return Arrays.asList(selector.selectImports(AnnotationMetadata.introspect(source)));
  }

  /** 对照用组合根：不做任何排除，用于证明被排除的配置本来是候选。 */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class UndecoratedAutoConfiguration {}
}

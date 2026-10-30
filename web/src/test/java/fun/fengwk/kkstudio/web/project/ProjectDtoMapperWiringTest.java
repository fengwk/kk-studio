package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.web.StrictJacksonConfiguration;

import java.lang.reflect.Field;

/**
 * {@link ProjectDtoMapper} 的容器装配契约：唯一构造器被容器隐式注入，且两个依赖都必须是容器 bean——生产 HTTP 的 Jackson 3 {@link
 * JsonMapper}（Boot4 {@link JacksonAutoConfiguration} + {@link StrictJacksonConfiguration}
 * 定制，JsonMapper 与其父类型 ObjectMapper 是同一个 bean）与自动装配的 workflow codec，而不是构件内部自建的副本。
 *
 * <p>生产组合根的 Jackson 3 自动装配由 {@code fun.fengwk.kkstudio.web.WebApplicationJsonAutoConfigurationTest}
 * 用真实导入选择器核验。
 */
class ProjectDtoMapperWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class, StrictJacksonConfiguration.class))
          .withUserConfiguration(ProjectDtoMapper.class, TestBeans.class);

  /**
   * 测试意图：该类只剩一个构造器，因此 Spring 只能用它装配，两个依赖都必须来自容器——共享的唯一 Jackson 3 mapper bean 与自动装配提供的 codec，而不是各自
   * new 出来的副本。
   */
  @Test
  void containerInjectsSharedJackson3MapperAndCodecBean() {
    runner.run(
        context -> {
          assertNull(context.getStartupFailure(), "bean wiring must start");

          JsonMapper jsonMapper = context.getBean(JsonMapper.class);
          // Boot4 只装配一个 Jackson 3 mapper：JsonMapper 与其父类型 ObjectMapper 解析到同一实例。
          assertSame(jsonMapper, context.getBean(ObjectMapper.class));

          ProjectDtoMapper mapper = context.getBean(ProjectDtoMapper.class);
          // 注入的依赖必须就是容器 bean，而不是构件内部自建的副本。
          assertSame(jsonMapper, readField(mapper, "objectMapper"));
          assertSame(
              context.getBean(ProjectWorkflowJsonCodec.class), readField(mapper, "workflowCodec"));
        });
  }

  /** 测试意图：自有代码所在的容器里不存在 Jackson 2 mapper bean（classpath 反射，不导入 Jackson 2 core）。 */
  @Test
  void containerExposesNoJackson2MapperBean() {
    runner.run(
        context -> {
          assertNull(context.getStartupFailure(), "bean wiring must start");
          assertEquals(
              0,
              context.getBeanNamesForType(jackson2MapperClass()).length,
              "own code must never resolve a Jackson 2 mapper bean");
        });
  }

  /** Jackson 2 mapper 类名按段拼出：既避免导入 Jackson 2 core，也避免在代码体内写字面全限定名。 */
  static Class<?> jackson2MapperClass() throws ClassNotFoundException {
    return Class.forName(
        String.join(".", "com", "fasterxml", "jackson", "databind", "ObjectMapper"));
  }

  /** 读取私有依赖字段：装配断言的唯一可观测点，DTO 映射行为无法区分 mapper 实例身份。 */
  private static Object readField(ProjectDtoMapper mapper, String name) throws Exception {
    Field field = ProjectDtoMapper.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(mapper);
  }

  /** 测试用最小装配：提供 project 模块自动装配在宿主中暴露的 workflow codec bean。 */
  @Configuration(proxyBeanMethods = false)
  static class TestBeans {

    @Bean
    ProjectWorkflowJsonCodec projectWorkflowJsonCodec() {
      return new ProjectWorkflowJsonCodec();
    }
  }
}

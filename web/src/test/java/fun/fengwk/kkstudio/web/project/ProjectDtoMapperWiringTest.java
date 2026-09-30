package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.common.json.jackson.ObjectMapperHolder;
import fun.fengwk.convention4j.springboot.starter.json.JacksonAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;

import java.lang.reflect.Field;

/**
 * {@link ProjectDtoMapper} 的容器装配契约：唯一构造器被容器隐式注入，且注入的是容器内共享配置的 Jackson2 {@link
 * ObjectMapper}，而不是构件内部自建的 mapper。
 *
 * <p>用最小 {@link ApplicationContextRunner}（与 web 既有 bean 装配测试同一基座）覆盖一次依赖解析，避免为一条装配断言启动全 context。
 */
class ProjectDtoMapperWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(ProjectDtoMapper.class, TestBeans.class);

  /**
   * 测试意图：该类只剩一个构造器，因此 Spring 只能用它装配，两个依赖都必须来自容器——mapper 是共享配置实例（convention4j starter 提供的
   * ObjectMapper bean），codec 是自动装配提供的 bean，而不是各自 new 出来的副本。
   */
  @Test
  void containerInjectsSharedConfiguredObjectMapperAndCodecBean() {
    runner.run(
        context -> {
          assertNull(context.getStartupFailure(), "bean wiring must start");

          ObjectMapper shared = context.getBean(ObjectMapper.class);
          // 容器里的 Jackson2 mapper 就是共享配置实例，生产用的是同一对象。
          assertSame(ObjectMapperHolder.getInstance(), shared);

          ProjectDtoMapper mapper = context.getBean(ProjectDtoMapper.class);
          // 注入的依赖必须就是容器 bean，而不是构件内部自建的副本。
          assertSame(shared, readField(mapper, "objectMapper"));
          assertSame(
              context.getBean(ProjectWorkflowJsonCodec.class), readField(mapper, "workflowCodec"));
        });
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

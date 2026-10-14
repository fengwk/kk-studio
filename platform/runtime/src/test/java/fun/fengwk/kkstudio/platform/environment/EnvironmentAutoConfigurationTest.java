package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;

import java.time.Clock;
import java.util.UUID;

/**
 * Platform-only 上下文的启动边界。
 *
 * <p>测试意图：Platform 不参与 Harness 组合，Environment 会话核心（{@code EnvironmentCapabilityTransport}）只由 web
 * 组合根装配。因此只装配 Platform 自动配置的上下文必须能启动，并且不得要求该传输：Skill 同步编排器的组合属于组合根，而不是 Platform 的自 动配置。
 */
class EnvironmentAutoConfigurationTest {

  @Test
  void platformOnlyContextStartsWithoutCapabilityTransport() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(EnvironmentAutoConfiguration.class);
      context.refresh();

      assertNotNull(context.getBean("environmentClock", Clock.class));
      assertNotNull(context.getBean("nodeInstanceId", UUID.class));
      assertTrue(
          context.getBeansOfType(EnvironmentSkillSyncOrchestrator.class).isEmpty(),
          "Platform 自动配置不得组合依赖 EnvironmentCapabilityTransport 的组件");
    }
  }
}

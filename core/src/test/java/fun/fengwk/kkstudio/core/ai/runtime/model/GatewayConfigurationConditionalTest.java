package fun.fengwk.kkstudio.core.ai.runtime.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.core.ai.runtime.tool.gateway.CoreToolGateway;
import fun.fengwk.kkstudio.core.ai.runtime.tool.gateway.HarnessToolGatewayConfiguration;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;

import java.time.Duration;

/**
 * 默认 Gateway bean 的抑制守卫：装配条件基于端口类型（{@link ModelGateway} / {@link ToolGateway}），任何自定义端口实现
 * 都会抑制默认实现，同时 bean 返回具体类型 {@link CoreModelGateway} / {@link CoreToolGateway}。
 */
class GatewayConfigurationConditionalTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(
              SystemSettingsSnapshot.class,
              () -> new SystemSettingsSnapshot(SystemSettings.DEFAULT));

  @Test
  void modelGatewayDefaultBeanIsSuppressedByAnyPortImplementation() {
    runner
        .withUserConfiguration(ModelExecutionConfiguration.class)
        .withBean(ProviderResolutionService.class, () -> (frozenType, request) -> null)
        .withBean(
            ModelGateway.class,
            () ->
                new ModelGateway() {
                  @Override
                  public StartResult start(Execution execution, Listener listener) {
                    return new ModelGateway.Busy(Duration.ofSeconds(1));
                  }
                })
        .run(
            context -> {
              // 自定义端口 bean 满足条件后默认实现被抑制：bean 定义不存在，getBean(端口类型) 取到自定义实现。
              assertFalse(context.containsBean("coreModelGateway"));
              assertFalse(
                  context.getBean(ModelGateway.class) instanceof CoreModelGateway,
                  "custom port implementation must win");
            });
  }

  @Test
  void toolGatewayDefaultBeanIsSuppressedByAnyPortImplementation() {
    runner
        .withUserConfiguration(HarnessToolGatewayConfiguration.class)
        .withBean(
            ToolGateway.class,
            () ->
                new ToolGateway() {
                  @Override
                  public PreflightResult preflight(ToolInvocationRequest request) {
                    return new ToolGateway.Allow();
                  }

                  @Override
                  public StartResult start(Execution execution, Listener listener) {
                    return new ToolGateway.Busy(Duration.ofSeconds(1));
                  }
                })
        .run(
            context -> {
              assertFalse(context.containsBean("coreToolGateway"));
              assertFalse(
                  context.getBean(ToolGateway.class) instanceof CoreToolGateway,
                  "custom port implementation must win");
            });
  }

  @Test
  void modelGatewayDefaultBeanIsCreatedWhenNoPortImplementationExists() {
    runner
        .withUserConfiguration(ModelExecutionConfiguration.class)
        .withBean(ProviderResolutionService.class, () -> (frozenType, request) -> null)
        .run(
            context -> {
              assertTrue(context.containsBean("coreModelGateway"));
              assertSame(
                  CoreModelGateway.class,
                  context.getBean(ModelGateway.class).getClass(),
                  "default bean must be the concrete CoreModelGateway");
            });
  }
}

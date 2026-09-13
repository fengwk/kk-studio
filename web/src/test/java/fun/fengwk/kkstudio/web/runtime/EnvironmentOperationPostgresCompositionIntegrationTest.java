package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationDispatcher;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.util.UUID;

/**
 * 意图：在真实 Web 上下文与 PostgreSQL 容器中验证三项关键装配契约： 1. 当 workers-enabled=false 时，常规 harnessRuntime 不启动
 * worker，但 environmentOperationDispatcher 独立自启动； 2. READY
 * 会话事件通过组合监听器同时唤醒工作分发器与操作分发器，且任一方抛出异常完全隔离，不影响另一方执行； 3. 真实 PostgreSQL 发送
 * pg_notify(EnvironmentOperationDispatcher.CHANNEL, ...) 能经由共享通知循环可靠唤醒操作分发器。
 */
class EnvironmentOperationPostgresCompositionIntegrationTest extends WebPostgresTestSupport {

  @MockitoBean private HarnessWorkDispatcher harnessWorkDispatcher;

  @Autowired private HarnessRuntimeProperties properties;
  @Autowired private EnvironmentSessionListener environmentSessionListener;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired
  @Qualifier("environmentOperationDispatcherLifecycle")
  private SmartLifecycle environmentOperationDispatcherLifecycle;

  @Autowired
  @Qualifier("harnessRuntimeLifecycle")
  private SmartLifecycle harnessRuntimeLifecycle;

  @Test
  void operationLifecycleRunsIndependentlyWhenWorkersDisabled() {
    assertFalse(properties.isWorkersEnabled(), "web 测试环境必须保持 workers-enabled=false");
    assertFalse(
        harnessRuntimeLifecycle.isRunning(),
        "harnessRuntimeLifecycle 严禁在 workers-enabled=false 时启动");
    assertTrue(
        environmentOperationDispatcherLifecycle.isRunning(),
        "environmentOperationDispatcherLifecycle 必须独立自启动");
    verify(environmentOperationDispatcher, atLeastOnce()).start();
  }

  @Test
  void readyEventInvokesBothWakesEvenIfOneThrows() {
    // 情况 A：harnessWorkDispatcher 异常不影响 environmentOperationDispatcher 唤醒
    doThrow(new RuntimeException("simulated work dispatcher failure"))
        .when(harnessWorkDispatcher)
        .wake();
    clearInvocations(environmentOperationDispatcher);

    environmentSessionListener.onEnvironmentReady(EnvironmentId.of(UUID.randomUUID()));

    verify(harnessWorkDispatcher, atLeastOnce()).wake();
    verify(environmentOperationDispatcher, atLeastOnce()).wake();

    // 情况 B：environmentOperationDispatcher 异常不影响 harnessWorkDispatcher 唤醒
    doThrow(new RuntimeException("simulated operation dispatcher failure"))
        .when(environmentOperationDispatcher)
        .wake();
    doNothing().when(harnessWorkDispatcher).wake();
    clearInvocations(harnessWorkDispatcher);

    environmentSessionListener.onEnvironmentReady(EnvironmentId.of(UUID.randomUUID()));

    verify(environmentOperationDispatcher, atLeastOnce()).wake();
    verify(harnessWorkDispatcher, atLeastOnce()).wake();
  }

  @Test
  void postgresNotificationInvokesOperationWake() {
    // 启动初始阶段的 resync 会触发 wake，在此清空历史记录以精确验证通知事件
    clearInvocations(environmentOperationDispatcher);

    jdbcTemplate.execute(
        "select pg_notify('" + EnvironmentOperationDispatcher.CHANNEL + "', 'test-wake')");

    verify(environmentOperationDispatcher, timeout(5000).atLeastOnce()).wake();
  }
}

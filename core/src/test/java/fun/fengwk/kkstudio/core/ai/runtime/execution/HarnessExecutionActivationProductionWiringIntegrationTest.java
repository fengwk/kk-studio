package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.POSTGRES;
import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.applyBaseline;
import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.SmartLifecycle;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconciler;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;

/** 验证生产配线能够启动 PostgreSQL 激活，并将 Environment READY 事件接入按路由筛选的持久化分发。 */
@SpringBootTest(
    classes = CoreTestApplication.class,
    properties = {"kk-studio.harness.runtime.workers-enabled=true", "spring.flyway.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HarnessExecutionActivationProductionWiringIntegrationTest {

  static {
    resetSchema();
  }

  @Autowired private PostgresqlExecutionActivationDispatcher dispatcher;
  @Autowired private PostgresqlExecutionActivationListener listener;
  @Autowired private EnvironmentReadyListener environmentReadyListener;
  @Autowired private PostgresqlExecutionActivationStore store;

  @Autowired
  @Qualifier("harnessExecutionActivationLifecycle")
  private SmartLifecycle activationLifecycle;

  @MockitoBean private ThreadReconciler threadReconciler;
  @MockitoBean private ModelWorker modelWorker;
  @MockitoBean private ToolWorker toolWorker;
  @MockitoBean private LiveEnvironmentRegistry environmentRegistry;

  @DynamicPropertySource
  static void overrideRuntimeInfrastructure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.multi.primary.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.multi.primary.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.multi.primary.username", POSTGRES::getUsername);
    registry.add("spring.datasource.multi.primary.password", POSTGRES::getPassword);
  }

  @Test
  void startsPostgresqlListenerAndWakesEnvironmentRouteWhenReady() {
    assertNotNull(dispatcher);
    assertNotNull(listener);
    assertTrue(activationLifecycle.isRunning());
    long platformToolId = 70_001L;
    long environmentToolId = 70_002L;
    when(environmentRegistry.listReady()).thenReturn(List.of());
    when(toolWorker.dispatch(anyLong()))
        .thenAnswer(
            invocation -> {
              long invocationId = invocation.getArgument(0);
              store.deleteIfExists(ExecutionTargetKind.TOOL_INVOCATION, invocationId);
              return true;
            });

    store.schedule(
        ExecutionTargetKind.TOOL_INVOCATION, platformToolId, null, Instant.now().minusSeconds(1));
    verify(toolWorker, timeout(5_000)).dispatch(platformToolId);

    store.schedule(
        ExecutionTargetKind.TOOL_INVOCATION,
        environmentToolId,
        "env-ready",
        Instant.now().minusSeconds(1));
    verify(toolWorker, after(300).never()).dispatch(environmentToolId);

    when(environmentRegistry.listReady()).thenReturn(List.of(readyEnvironment("env-ready")));
    environmentReadyListener.onEnvironmentReady("env-ready");

    verify(toolWorker, timeout(5_000)).dispatch(environmentToolId);
  }

  private static LiveEnvironment readyEnvironment(String name) {
    return new LiveEnvironment(
        name,
        LiveEnvironmentStatus.READY,
        new ReadyConnection(),
        List.<DaemonSkillDescriptor>of(),
        Instant.now());
  }

  private static void resetSchema() {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
      applyBaseline(connection);
    } catch (Exception error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  private static final class ReadyConnection implements EnvironmentDaemonConnection {

    @Override
    public String connectionId() {
      return "ready-connection";
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void sendText(String text) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      // 仅供测试使用的不可变连接。
    }
  }
}

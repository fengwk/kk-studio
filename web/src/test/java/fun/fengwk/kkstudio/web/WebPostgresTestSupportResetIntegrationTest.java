package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialRefreshDispatcher;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationLoop;
import fun.fengwk.kkstudio.web.runtime.PluginCatalogRefresher;

import java.util.List;

/**
 * 共享 Schema 重建与后台数据库生命周期协调的契约测试。
 *
 * <p>真实 Spring 上下文中的五个数据库后台生命周期由 {@link MockitoSpyBean} 包装为 spy（仍调用真实实现）。测试确认自动 {@code @BeforeEach}
 * 后全部处于运行状态，再手动执行基座的 package 可见 reset：全部 stop 先于 Schema 重建，start 只在 fixture seed/配置恢复之后出现；原本已停止的
 * bean 不会被 reset 启动，其余 bean 恢复运行。另固定「类结束关闭 Context」的基座约定。
 */
class WebPostgresTestSupportResetIntegrationTest extends WebPostgresTestSupport {

  @MockitoSpyBean
  @Qualifier("postgresqlNotificationLoop")
  private PostgresqlNotificationLoop postgresqlNotificationLoop;

  @MockitoSpyBean
  @Qualifier("realtimeNotificationLoop")
  private PostgresqlNotificationLoop realtimeNotificationLoop;

  @MockitoSpyBean private PluginCatalogRefresher pluginCatalogRefresher;

  @MockitoSpyBean private PluginCredentialRefreshDispatcher pluginCredentialRefreshDispatcher;

  @MockitoSpyBean private StorageMaintenance storageMaintenance;

  @Autowired private JdbcClient jdbcClient;

  @BeforeEach
  void allDatabaseLifecyclesRunAfterAutomaticReset() {
    for (SmartLifecycle lifecycle : databaseLifecycles()) {
      assertTrue(
          lifecycle.isRunning(),
          () -> lifecycle.getClass().getName() + " must be running before the manual reset");
    }
  }

  @Test
  void manualResetStopsAllDatabaseLifecyclesBeforeRestartingThem() throws Exception {
    List<SmartLifecycle> lifecycles = databaseLifecycles();
    clearInvocations(lifecycles.toArray());

    resetAndApplySchema();

    InOrder inOrder = inOrder(lifecycles.toArray());
    for (SmartLifecycle lifecycle : lifecycles) {
      inOrder.verify(lifecycle).stop();
    }
    for (SmartLifecycle lifecycle : lifecycles) {
      inOrder.verify(lifecycle).start();
    }
    for (SmartLifecycle lifecycle : lifecycles) {
      verify(lifecycle).stop();
      verify(lifecycle).start();
      assertTrue(
          lifecycle.isRunning(),
          () -> lifecycle.getClass().getName() + " must run again after the reset");
    }
    // reset 会重建 schema 并重新执行 baseline/seed，fixture 默认助手必须仍在。
    assertEquals(
        1L,
        jdbcClient
            .sql("select count(*) from agent_definition where name = 'default-assistant'")
            .query(Long.class)
            .single());
  }

  @Test
  void manualResetDoesNotStartALifecycleThatWasAlreadyStopped() throws Exception {
    // 只暂停一个 bean：reset 不得启动它；仅在 Schema 已恢复时清理现场，不重启半迁移库。
    PluginCredentialRefreshDispatcher stopped = pluginCredentialRefreshDispatcher;
    stopped.stop();
    assertFalse(stopped.isRunning());
    clearInvocations(databaseLifecycles().toArray());

    boolean resetCompleted = false;
    try {
      resetAndApplySchema();
      resetCompleted = true;

      verify(stopped, never()).stop();
      verify(stopped, never()).start();
      assertFalse(stopped.isRunning());

      InOrder inOrder =
          inOrder(
              postgresqlNotificationLoop,
              realtimeNotificationLoop,
              pluginCatalogRefresher,
              storageMaintenance);
      for (SmartLifecycle lifecycle : runningExcept(stopped)) {
        inOrder.verify(lifecycle).stop();
      }
      for (SmartLifecycle lifecycle : runningExcept(stopped)) {
        inOrder.verify(lifecycle).start();
      }
      for (SmartLifecycle lifecycle : runningExcept(stopped)) {
        verify(lifecycle).stop();
        verify(lifecycle).start();
        assertTrue(lifecycle.isRunning());
      }
      assertEquals(
          1L,
          jdbcClient
              .sql("select count(*) from agent_definition where name = 'default-assistant'")
              .query(Long.class)
              .single());
    } finally {
      if (resetCompleted && !stopped.isRunning()) {
        stopped.start();
      }
    }
  }

  @Test
  void supportDirtiesItsContextAfterEachClass() {
    // 每个测试类结束后关闭自己的 Context，避免其它缓存 Context 的后台事务继续访问共享 schema。
    DirtiesContext dirtiesContext =
        WebPostgresTestSupport.class.getAnnotation(DirtiesContext.class);
    assertNotNull(dirtiesContext, "WebPostgresTestSupport must declare @DirtiesContext");
    assertEquals(DirtiesContext.ClassMode.AFTER_CLASS, dirtiesContext.classMode());
  }

  private List<SmartLifecycle> databaseLifecycles() {
    return List.of(
        postgresqlNotificationLoop,
        realtimeNotificationLoop,
        pluginCatalogRefresher,
        pluginCredentialRefreshDispatcher,
        storageMaintenance);
  }

  private List<SmartLifecycle> runningExcept(SmartLifecycle stopped) {
    return databaseLifecycles().stream().filter(lifecycle -> lifecycle != stopped).toList();
  }
}

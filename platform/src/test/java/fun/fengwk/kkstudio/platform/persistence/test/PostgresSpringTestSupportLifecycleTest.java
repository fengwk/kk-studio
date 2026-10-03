package fun.fengwk.kkstudio.platform.persistence.test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContextManager;

import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;

class PostgresSpringTestSupportLifecycleTest {

  /** 通过真实 Spring 类生命周期证明每类结束即释放连接池和后台维护，下一类不复用已关闭的资源。 */
  @Test
  void closesClassResourcesBeforeStartingNextClass() throws Exception {
    Resources first = runClassLifecycle(new FirstFixture(), null);
    runClassLifecycle(new SecondFixture(), first);
  }

  private Resources runClassLifecycle(PostgresSpringTestSupport fixture, Resources previous)
      throws Exception {
    TestContextManager manager = new TestContextManager(fixture.getClass());
    ConfigurableApplicationContext context = null;
    try {
      manager.beforeTestClass();
      manager.prepareTestInstance(fixture);
      context = (ConfigurableApplicationContext) manager.getTestContext().getApplicationContext();
      fixture.resetAndApplySchema();
      HikariDataSource pool = context.getBean(HikariDataSource.class);
      StorageMaintenance maintenance = context.getBean(StorageMaintenance.class);
      Resources current = new Resources(context, pool, maintenance);
      assertAll(
          () -> assertTrue(current.context().isActive()),
          () -> assertFalse(pool.isClosed()),
          () -> assertTrue(maintenance.isRunning()));
      if (previous != null) {
        assertAll(
            () -> assertNotSame(previous.context(), current.context()),
            () -> assertNotSame(previous.pool(), pool),
            () -> assertNotSame(previous.maintenance(), maintenance));
      }

      manager.afterTestClass();

      assertAll(
          () -> assertFalse(current.context().isActive(), "class context must close"),
          () -> assertTrue(pool.isClosed(), "class pool must close"),
          () -> assertFalse(maintenance.isRunning(), "class maintenance must stop"));
      return current;
    } finally {
      // 即使生命周期断言失败，也清理缓存和真实资源，避免负控污染后续共享数据库测试。
      try {
        manager.getTestContext().markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
      } finally {
        if (context != null) {
          context.close();
        }
      }
    }
  }

  private record Resources(
      ConfigurableApplicationContext context,
      HikariDataSource pool,
      StorageMaintenance maintenance) {}

  static class FirstFixture extends PostgresSpringTestSupport {}

  static class SecondFixture extends PostgresSpringTestSupport {}
}

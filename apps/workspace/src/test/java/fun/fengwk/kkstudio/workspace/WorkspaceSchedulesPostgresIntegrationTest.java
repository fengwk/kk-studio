package fun.fengwk.kkstudio.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.account.StudioUserLock;
import fun.fengwk.kkstudio.platform.harness.store.HarnessPlacementQuery;
import fun.fengwk.kkstudio.workspace.dto.ScheduleDraftDTO;
import fun.fengwk.kkstudio.workspace.dto.SchedulePatchDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTargetDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTriggerDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTriggerDTO.Frequency;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTriggerDTO.Type;

import java.util.UUID;

/** Real PostgreSQL persistence coverage for schedule configuration, due work, and run history. */
@Testcontainers
class WorkspaceSchedulesPostgresIntegrationTest {
  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
  }

  /** A schedule's configuration, due work, execution record, and deletion stay in sync. */
  @Test
  void scheduleCreateChangeDueDisableAndDelete() {
    try (var context = serviceContext()) {
      var jdbc = context.getBean(JdbcClient.class);
      var spaces = context.getBean(ChatSpaceService.class);
      var schedules = context.getBean(WorkspaceSchedules.class);
      var owner = testUser(context, "schedule-lifecycle");
      var space =
          spaces.create(
              owner,
              UUID.randomUUID(),
              new ChatSpaceService.Create("Schedules", "Root", null, null, false));
      UUID folder =
          jdbc.sql("select id from workspace_folder where workspace_id=:space")
              .param("space", space.id())
              .query(UUID.class)
              .single();
      jdbc.sql("insert into agent (name, config) values ('test-agent', '{}'::jsonb)").update();
      var target =
          new ScheduleTargetDTO(
              ScheduleTargetDTO.Type.NEW_SESSION, folder, "test-agent", null, null, null, null);
      var trigger =
          new ScheduleTriggerDTO(Type.REPEAT, Frequency.CRON, null, null, null, null, "* * * * *");
      UUID key = UUID.randomUUID();
      var created =
          schedules.create(
              owner,
              space.id(),
              key,
              new ScheduleDraftDTO("Daily check", "Check status", true, trigger, "UTC", target));
      assertEquals(1, created.version());
      assertNotNull(created.nextDueAt());
      assertEquals(created.id(), schedules.list(owner, space.id()).getFirst().id());
      assertEquals(
          1,
          jdbc.sql(
                  "select count(*) from durable_work where target_type='SCHEDULE' and target_id=:id")
              .param("id", created.id())
              .query(Integer.class)
              .single());

      var changedTrigger =
          new ScheduleTriggerDTO(
              Type.REPEAT, Frequency.CRON, null, null, null, null, "*/2 * * * *");
      var changed =
          schedules.patch(
              owner,
              space.id(),
              created.id(),
              new SchedulePatchDTO(
                  "1", "Changed", "Updated message", null, changedTrigger, null, null));
      assertEquals(2, changed.version());
      assertEquals("Changed", changed.title());
      assertEquals("Updated message", changed.message());
      assertNotNull(changed.nextDueAt());
      assertEquals(changedTrigger, changed.trigger());

      jdbc.sql(
              "update workspace_schedule set next_due_at=date_trunc('milliseconds', statement_timestamp() - interval '2 minutes') where id=:id")
          .param("id", created.id())
          .update();
      jdbc.sql(
              "update durable_work set available_at=(select next_due_at from workspace_schedule where id=:id) where target_type='SCHEDULE' and target_id=:id")
          .param("id", created.id())
          .update();
      var claim = schedules.claim().orElseThrow();
      assertEquals(created.id(), claim.targetId());
      // Releasing an acquired due item makes it available to another dispatcher attempt.
      schedules.release(claim);
      var reclaimed = schedules.claim().orElseThrow();
      assertEquals(created.id(), reclaimed.targetId());
      schedules.auto(reclaimed);
      var run = schedules.runs(owner, space.id(), created.id(), null).items().getFirst();
      assertEquals("SKIPPED", run.result().name());
      assertEquals("EXECUTION_NOT_SETTLED", run.reason());
      assertNotNull(run.scheduledAt());
      assertNotNull(schedules.list(owner, space.id()).getFirst().nextDueAt());

      var disabled =
          schedules.patch(
              owner,
              space.id(),
              created.id(),
              new SchedulePatchDTO("2", null, null, false, null, null, null));
      assertEquals(3, disabled.version());
      assertFalse(disabled.enabled());
      assertNull(disabled.nextDueAt());
      assertEquals(
          0,
          jdbc.sql(
                  "select count(*) from durable_work where target_type='SCHEDULE' and target_id=:id")
              .param("id", created.id())
              .query(Integer.class)
              .single());

      var enabled =
          schedules.patch(
              owner,
              space.id(),
              created.id(),
              new SchedulePatchDTO("3", null, null, true, null, null, null));
      assertEquals(4, enabled.version());
      assertTrue(enabled.enabled());
      assertNotNull(enabled.nextDueAt());
      assertEquals(
          1,
          jdbc.sql(
                  "select count(*) from durable_work where target_type='SCHEDULE' and target_id=:id")
              .param("id", created.id())
              .query(Integer.class)
              .single());

      schedules.delete(owner, space.id(), created.id(), 4);
      assertTrue(schedules.list(owner, space.id()).isEmpty());
      assertEquals(
          0,
          jdbc.sql(
                  "select count(*) from durable_work where target_type='SCHEDULE' and target_id=:id")
              .param("id", created.id())
              .query(Integer.class)
              .single());
      assertEquals(
          0,
          jdbc.sql("select count(*) from workspace_schedule_run where schedule_id=:id")
              .param("id", created.id())
              .query(Integer.class)
              .single());
    }
  }

  private static StudioPrincipal testUser(
      AnnotationConfigApplicationContext context, String username) {
    UUID id = UUID.randomUUID();
    context
        .getBean(JdbcClient.class)
        .sql(
            """
        insert into studio_user (id, username, role, password_hash)
        values (:id, :username, 'USER', '{noop}workspace-test-password')
        """)
        .param("id", id)
        .param("username", username)
        .update();
    return new StudioPrincipal(id, username, StudioRole.USER);
  }

  private static AnnotationConfigApplicationContext serviceContext() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var context = new AnnotationConfigApplicationContext();
    context.register(
        TransactionConfig.class,
        ChatSpaceService.class,
        FolderService.class,
        WorkspacePlacements.class,
        WorkspaceResourceOwnership.class,
        WorkspaceSchedules.class,
        ScheduleRepository.class,
        StudioUserLock.class);
    context.registerBean(JdbcClient.class, () -> JdbcClient.create(dataSource));
    context.registerBean(
        JdbcTransactionManager.class, () -> new JdbcTransactionManager(dataSource));
    context.registerBean(HarnessPlacementQuery.class, () -> mock(HarnessPlacementQuery.class));
    context.registerBean(ScheduleTargets.class, () -> mock(ScheduleTargets.class));
    context.refresh();
    return context;
  }

  @EnableTransactionManagement(proxyTargetClass = true)
  static class TransactionConfig {}
}

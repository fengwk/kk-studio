package fun.fengwk.kkstudio.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Testcontainers
class WorkspacePostgresIntegrationTest {
  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
  }

  /** Both transactions try to archive different space rows; exactly one may succeed. */
  @Test
  void concurrentArchivePreservesAnActiveSpace() throws Exception {
    try (var context = serviceContext();
        var executor = Executors.newFixedThreadPool(2)) {
      var service = context.getBean(ChatSpaceService.class);
      var owner = testUser(context, "archive-owner");
      var first = create(service, owner, "First");
      var second = create(service, owner, "Second");
      var barrier = new CyclicBarrier(2);
      var a = executor.submit(() -> archive(service, owner, first.id(), barrier));
      var b = executor.submit(() -> archive(service, owner, second.id(), barrier));
      assertEquals(
          Set.of("ARCHIVED", "LAST_ACTIVE_CHAT_SPACE"),
          Set.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)));
      assertEquals(
          1, service.list(owner).stream().filter(space -> space.archivedAt() == null).count());
    }
  }

  /**
   * Mutating a space changes only the config version and cannot alter the stored creation result.
   */
  @Test
  void patchChecksOwnerAndVersionAndCreateReplaysOriginal() {
    try (var context = serviceContext()) {
      var service = context.getBean(ChatSpaceService.class);
      var owner = testUser(context, "replay-owner");
      var outsider = testUser(context, "replay-outsider");
      var key = UUID.randomUUID();
      var request = new ChatSpaceService.Create("Original", "Root", null, null, false);
      var space = service.create(owner, key, request);
      var rename = new ChatSpaceService.Patch(0, "Renamed", null, null, null, null);
      assertEquals(
          DomainErrorCode.NOT_FOUND,
          assertThrows(DomainException.class, () -> service.patch(outsider, space.id(), rename))
              .code());
      assertEquals(0, service.list(outsider).size());
      var renamed = service.patch(owner, space.id(), rename);
      assertEquals(1, renamed.version());
      assertEquals(space.treeVersion(), renamed.treeVersion());
      assertEquals(
          DomainErrorCode.VERSION_CONFLICT,
          assertThrows(DomainException.class, () -> service.patch(owner, space.id(), rename))
              .code());
      assertEquals(space, service.create(owner, key, request));
      assertEquals(
          "IDEMPOTENCY_KEY_REUSED",
          assertThrows(
                  DomainException.class,
                  () ->
                      service.create(
                          owner,
                          key,
                          new ChatSpaceService.Create("Different", "Root", null, null, false)))
              .reason());
      assertEquals("Renamed", service.list(owner).getFirst().title());
    }
  }

  private static String archive(
      ChatSpaceService service, StudioPrincipal owner, UUID id, CyclicBarrier barrier)
      throws Exception {
    barrier.await(5, TimeUnit.SECONDS);
    try {
      service.patch(owner, id, new ChatSpaceService.Patch(0, null, true, null, null, null));
      return "ARCHIVED";
    } catch (DomainException ex) {
      return ex.reason();
    }
  }

  private static ChatSpaceService.Space create(
      ChatSpaceService service, StudioPrincipal owner, String title) {
    return service.create(
        owner, UUID.randomUUID(), new ChatSpaceService.Create(title, "Root", null, null, false));
  }

  private static StudioPrincipal testUser(
      AnnotationConfigApplicationContext context, String username) {
    UUID id = UUID.randomUUID();
    context
        .getBean(JdbcClient.class)
        .sql(
            """
        insert into studio_user (id, username, role, password_hash)
        select :id, :username, 'USER', password_hash from studio_user where username = 'kkstudio'
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
    context.register(TransactionConfig.class, ChatSpaceService.class);
    context.registerBean(JdbcClient.class, () -> JdbcClient.create(dataSource));
    context.registerBean(
        JdbcTransactionManager.class, () -> new JdbcTransactionManager(dataSource));
    context.refresh();
    return context;
  }

  @EnableTransactionManagement
  static class TransactionConfig {}

  /** Root directories share the same uniqueness boundary despite a null parent. */
  @Test
  void nullParentStillEnforcesSiblingName() throws SQLException {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var sql = connection.createStatement()) {
      connection.setAutoCommit(false);
      sql.executeUpdate(
          """
          insert into chat_space (id, owner_user_id, title, title_key, create_request_hash)
          select '00000000-0000-0000-0000-000000000010', id, 'Test A', 'test a', repeat('0', 64)
          from studio_user where username = 'kkstudio'
          """);
      sql.executeUpdate(
          """
          insert into workspace_folder (id, workspace_id, name, name_key, position)
          values ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000010', 'Root', 'root', 0)
          """);
      var error =
          assertThrows(
              PSQLException.class,
              () ->
                  sql.executeUpdate(
                      """
          insert into workspace_folder (id, workspace_id, name, name_key, position)
          values ('00000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000010', 'ROOT', 'root', 1)
          """));
      assertEquals("uk_workspace_folder_name", error.getServerErrorMessage().getConstraint());
      connection.rollback();
    }
  }

  /** Swaps may temporarily collide, but a duplicate position cannot commit. */
  @Test
  void positionUniquenessIsDeferredUntilCommit() throws SQLException {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var sql = connection.createStatement()) {
      connection.setAutoCommit(false);
      sql.executeUpdate(
          """
          insert into chat_space (id, owner_user_id, title, title_key, create_request_hash)
          select '00000000-0000-0000-0000-000000000020', id, 'Test B', 'test b', repeat('0', 64)
          from studio_user where username = 'kkstudio'
          """);
      assertEquals(
          2,
          sql.executeUpdate(
              """
          insert into workspace_folder (id, workspace_id, name, name_key, position) values
              ('00000000-0000-0000-0000-000000000021', '00000000-0000-0000-0000-000000000020', 'A', 'a', 0),
              ('00000000-0000-0000-0000-000000000022', '00000000-0000-0000-0000-000000000020', 'B', 'b', 0)
          """));
      var error = assertThrows(PSQLException.class, connection::commit);
      assertEquals("uk_workspace_folder_position", error.getServerErrorMessage().getConstraint());
      connection.rollback();
    }
  }
}

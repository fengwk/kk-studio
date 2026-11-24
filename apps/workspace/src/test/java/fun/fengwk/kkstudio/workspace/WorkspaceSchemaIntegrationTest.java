package fun.fengwk.kkstudio.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;

@Testcontainers
class WorkspaceSchemaIntegrationTest {
  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
  }

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
          insert into chat_space (id, owner_user_id, title, title_key)
          select '00000000-0000-0000-0000-000000000010', id, 'Test A', 'test a'
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
          insert into chat_space (id, owner_user_id, title, title_key)
          select '00000000-0000-0000-0000-000000000020', id, 'Test B', 'test b'
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

package fun.fengwk.kkstudio.platform.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Repository SQL and row mapping are verified against PostgreSQL, including account/session state.
 */
class StudioAccountRepositoryIntegrationTest {
  private StudioAccountRepository repository;

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection connection = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(connection);
      PostgresSchemaSupport.applyBaseline(connection);
    }
    JdbcClient jdbc =
        JdbcClient.create(
            new SimpleDriverDataSource(
                new Driver(),
                PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
                PostgresSchemaSupport.POSTGRES.getUsername(),
                PostgresSchemaSupport.POSTGRES.getPassword()));
    repository = new StudioAccountRepository(jdbc, JsonMapper.builder().build());
  }

  @Test
  void accountRowsRoundTripAndReflectDisableAndUpdate() {
    UUID id = UUID.randomUUID();
    repository.create(id, "alice", "hash-1", StudioRole.ADMIN);

    StudioAccountRepository.User created = repository.byUsername("alice").orElseThrow();
    assertEquals(id, created.id());
    assertEquals("hash-1", created.passwordHash());
    assertEquals(StudioRole.ADMIN, created.role());
    assertTrue(created.enabled());
    assertEquals("{}", repository.profile(id).toString());
    assertTrue(repository.list().contains(created));

    repository.save(id, "alice.2", "hash-2", StudioRole.USER, false);

    StudioAccountRepository.User updated = repository.byId(id).orElseThrow();
    assertEquals("alice.2", updated.username());
    assertEquals("hash-2", updated.passwordHash());
    assertEquals(StudioRole.USER, updated.role());
    assertFalse(updated.enabled());
    assertTrue(repository.byUsername("alice").isEmpty());
  }

  @Test
  void rowLocksAndEnabledAdminCountMatchPersistedAccountState() {
    long initialCount = repository.countEnabledAdmins();
    UUID adminId = UUID.randomUUID();
    UUID disabledAdminId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    repository.create(adminId, "admin", "hash", StudioRole.ADMIN);
    repository.create(disabledAdminId, "disabled", "hash", StudioRole.ADMIN);
    repository.create(userId, "user", "hash", StudioRole.USER);
    repository.save(disabledAdminId, "disabled", null, StudioRole.ADMIN, false);

    assertEquals(adminId, repository.lockById(adminId).orElseThrow().id());
    assertTrue(repository.lockById(UUID.randomUUID()).isEmpty());
    assertEquals(initialCount + 1, repository.countEnabledAdmins());

    repository.lockAdminMutations();

    repository.save(adminId, "admin", null, StudioRole.ADMIN, false);
    assertEquals(initialCount, repository.countEnabledAdmins());
  }

  @Test
  void sessionsAreBoundToEnabledUsersAndDeletionOperationsAreScoped() {
    UUID firstUser = UUID.randomUUID();
    UUID secondUser = UUID.randomUUID();
    repository.create(firstUser, "first", "hash", StudioRole.USER);
    repository.create(secondUser, "second", "hash", StudioRole.USER);
    byte[] firstToken = new byte[] {1, 2, 3};
    byte[] secondToken = new byte[] {4, 5, 6};
    byte[] otherUserToken = new byte[] {7, 8, 9};
    repository.session(firstToken, firstUser);
    repository.session(secondToken, firstUser);
    repository.session(otherUserToken, secondUser);

    assertEquals(firstUser, repository.sessionPrincipal(firstToken).orElseThrow().id());
    repository.deleteSessionsExcept(firstUser, firstToken);
    assertTrue(repository.sessionPrincipal(secondToken).isEmpty());
    assertTrue(repository.sessionPrincipal(otherUserToken).isPresent());
    repository.deleteSession(firstToken);
    assertTrue(repository.sessionPrincipal(firstToken).isEmpty());

    repository.session(firstToken, firstUser);
    repository.deleteSessions(firstUser);
    assertTrue(repository.sessionPrincipal(firstToken).isEmpty());
    assertTrue(repository.sessionPrincipal(otherUserToken).isPresent());
  }

  @Test
  void expiredSessionsAreDeletedWithoutAffectingOtherUsers() throws SQLException {
    UUID userId = UUID.randomUUID();
    UUID otherId = UUID.randomUUID();
    repository.create(userId, "alice", "hash", StudioRole.USER);
    repository.create(otherId, "bob", "hash", StudioRole.USER);
    byte[] expired = new byte[] {10};
    byte[] active = new byte[] {11};
    byte[] other = new byte[] {12};
    repository.session(expired, userId);
    repository.session(active, userId);
    repository.session(other, otherId);
    try (Connection connection = PostgresSchemaSupport.newConnection();
        var statement =
            connection.prepareStatement(
                "update login_session set expires_at = current_timestamp - interval '1 second' where token_hash = ?")) {
      statement.setBytes(1, expired);
      statement.executeUpdate();
    }

    repository.deleteExpired(userId);

    assertTrue(repository.sessionPrincipal(expired).isEmpty());
    assertTrue(repository.sessionPrincipal(active).isPresent());
    assertTrue(repository.sessionPrincipal(other).isPresent());
  }
}

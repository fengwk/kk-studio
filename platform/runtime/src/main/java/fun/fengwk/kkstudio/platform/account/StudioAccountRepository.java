package fun.fengwk.kkstudio.platform.account;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class StudioAccountRepository {
  private static final String USER_COLUMNS =
      """
      id, username, password_hash, role, disabled_at is null as enabled, created_at
      """;

  private final JdbcClient jdbc;
  private final JsonMapper mapper;

  StudioAccountRepository(JdbcClient jdbc, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  JsonNode profile(UUID id) {
    return mapper.readTree(
        jdbc.sql(
                """
        select profile::text
        from studio_user
        where id = :id
        """)
            .param("id", id)
            .query(String.class)
            .single());
  }

  record User(
      UUID id,
      String username,
      String passwordHash,
      StudioRole role,
      boolean enabled,
      Instant createdAt) {}

  Optional<User> byUsername(String username) {
    return jdbc.sql(
            """
        select %s
        from studio_user
        where username = :username
        """
                .formatted(USER_COLUMNS))
        .param("username", username)
        .query(this::map)
        .optional();
  }

  Optional<User> lockById(UUID id) {
    return jdbc.sql(
            """
        select %s
        from studio_user
        where id = :id
        for update
        """
                .formatted(USER_COLUMNS))
        .param("id", id)
        .query(this::map)
        .optional();
  }

  Optional<User> byId(UUID id) {
    return jdbc.sql(
            """
        select %s
        from studio_user
        where id = :id
        """
                .formatted(USER_COLUMNS))
        .param("id", id)
        .query(this::map)
        .optional();
  }

  List<User> list() {
    return jdbc.sql(
            """
        select %s
        from studio_user
        order by username
        """
                .formatted(USER_COLUMNS))
        .query(this::map)
        .list();
  }

  private User map(ResultSet rs, int row) throws SQLException {
    return new User(
        rs.getObject("id", UUID.class),
        rs.getString("username"),
        rs.getString("password_hash"),
        StudioRole.valueOf(rs.getString("role")),
        rs.getBoolean("enabled"),
        rs.getTimestamp("created_at").toInstant());
  }

  void lockAdminMutations() {
    jdbc.sql("select pg_advisory_xact_lock(130, 1)").query().singleRow();
  }

  boolean hasUsers() {
    return jdbc.sql("select exists (select 1 from studio_user)").query(Boolean.class).single();
  }

  long countEnabledAdmins() {
    return jdbc.sql(
            """
            select count(*)
            from studio_user
            where role = 'ADMIN' and disabled_at is null
            """)
        .query(Long.class)
        .single();
  }

  void create(UUID id, String username, String hash, StudioRole role) {
    jdbc.sql(
            """
            insert into studio_user (id, username, password_hash, role)
            values (:id, :username, :hash, :role)
            """)
        .param("id", id)
        .param("username", username)
        .param("hash", hash)
        .param("role", role.name())
        .update();
  }

  void save(UUID id, String username, String hash, StudioRole role, Boolean enabled) {
    jdbc.sql(
            """
            update studio_user
            set username = :username,
                password_hash = coalesce(:hash, password_hash),
                role = :role,
                disabled_at = case when :enabled then null else coalesce(disabled_at, current_timestamp) end,
                updated_at = current_timestamp
            where id = :id
            """)
        .param("id", id)
        .param("username", username)
        .param("hash", hash)
        .param("role", role.name())
        .param("enabled", enabled)
        .update();
  }

  void session(byte[] tokenHash, UUID userId) {
    jdbc.sql(
            """
            insert into login_session (token_hash, user_id, expires_at)
            values (:hash, :user, current_timestamp + interval '30 days')
            """)
        .param("hash", tokenHash)
        .param("user", userId)
        .update();
  }

  Optional<User> sessionPrincipal(byte[] tokenHash) {
    return jdbc.sql(
            """
            select %s
            from studio_user
            where disabled_at is null
              and id = (select user_id from login_session
                        where token_hash = :hash and expires_at > current_timestamp)
            """
                .formatted(USER_COLUMNS))
        .param("hash", tokenHash)
        .query(this::map)
        .optional();
  }

  void deleteSession(byte[] hash) {
    jdbc.sql("delete from login_session where token_hash = :hash").param("hash", hash).update();
  }

  void deleteSessionsExcept(UUID userId, byte[] tokenHash) {
    jdbc.sql(
            """
            delete from login_session
            where user_id = :user and token_hash <> :hash
            """)
        .param("user", userId)
        .param("hash", tokenHash)
        .update();
  }

  void deleteSessions(UUID userId) {
    jdbc.sql("delete from login_session where user_id = :user").param("user", userId).update();
  }

  void deleteExpired(UUID userId) {
    jdbc.sql(
            """
            delete from login_session
            where user_id = :user and expires_at <= current_timestamp
            """)
        .param("user", userId)
        .update();
  }
}

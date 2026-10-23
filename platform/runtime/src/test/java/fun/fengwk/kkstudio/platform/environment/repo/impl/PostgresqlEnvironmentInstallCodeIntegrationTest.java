package fun.fengwk.kkstudio.platform.environment.repo.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.platform.environment.RegistrationTokens;
import fun.fengwk.kkstudio.platform.environment.repo.impl.store.EnvironmentJdbcStore;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentInstallCodes;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * {@link PostgresqlEnvironmentRepository} 临时安装 code 持久化操作在真实 PostgreSQL 17 上的集成测试。
 *
 * <p>验证目标包括：一次性原子消费语义（消费后物理删除）、严格过期边界检查（{@code expires_at > now}）、upsert 替换覆盖旧记录、凭据不匹配安全性以及外键级联删除。
 */
class PostgresqlEnvironmentInstallCodeIntegrationTest {

  private static JdbcClient jdbcClient;

  private PostgresqlEnvironmentRepository repository;

  @BeforeAll
  static void beforeAll() {
    jdbcClient =
        JdbcClient.create(
            new SimpleDriverDataSource(
                new Driver(),
                PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
                PostgresSchemaSupport.POSTGRES.getUsername(),
                PostgresSchemaSupport.POSTGRES.getPassword()));
  }

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    EnvironmentJdbcStore store = new EnvironmentJdbcStore(jdbcClient);
    PostgresqlEnvironmentChangeNotifier notifier = mock(PostgresqlEnvironmentChangeNotifier.class);
    repository = new PostgresqlEnvironmentRepository(store, notifier, jdbcClient);
  }

  /** 测试意图：临时安装 code 在成功兑换后被物理删除，第二次兑换必须返回 false。 */
  @Test
  void consumeOnceSucceedsThenSubsequentAttemptsFail() {
    UUID envId = insertEnvironment("env-alpha", "token-alpha");
    String tokenHash = RegistrationTokens.hash("token-alpha");
    String code = EnvironmentInstallCodes.generate();
    String codeHash = EnvironmentInstallCodes.hash(code);
    Instant now = Instant.parse("2026-10-23T10:00:00.000Z");
    Instant expiresAt = now.plus(10, ChronoUnit.MINUTES);

    repository.saveInstallCode(envId, codeHash, tokenHash, expiresAt);
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    boolean firstConsume = repository.consumeInstallCode(envId, codeHash, tokenHash, now);
    assertThat(firstConsume).isTrue();
    assertThat(countInstallCodes(envId)).isZero();

    boolean secondConsume = repository.consumeInstallCode(envId, codeHash, tokenHash, now);
    assertThat(secondConsume).isFalse();
  }

  /** 测试意图：过期检查必须严格满足 expires_at > now，等于或晚于过期时间均拒绝消费。 */
  @Test
  void expiryBoundaryStrictlyRequiresExpiresAtGreaterThanNow() {
    UUID envId = insertEnvironment("env-beta", "token-beta");
    String tokenHash = RegistrationTokens.hash("token-beta");
    String code = EnvironmentInstallCodes.generate();
    String codeHash = EnvironmentInstallCodes.hash(code);
    Instant expiresAt = Instant.parse("2026-10-23T12:00:00.000Z");

    repository.saveInstallCode(envId, codeHash, tokenHash, expiresAt);

    // 已过期或恰好到期时拒绝消费，并保留记录。
    Instant justAfter = expiresAt.plusMillis(1);
    boolean consumedAfter = repository.consumeInstallCode(envId, codeHash, tokenHash, justAfter);
    assertThat(consumedAfter).isFalse();
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    boolean consumedExact = repository.consumeInstallCode(envId, codeHash, tokenHash, expiresAt);
    assertThat(consumedExact).isFalse();
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    // 到期前一毫秒仍可消费，成功后物理删除。
    Instant justBefore = expiresAt.minusMillis(1);
    boolean consumedBefore = repository.consumeInstallCode(envId, codeHash, tokenHash, justBefore);
    assertThat(consumedBefore).isTrue();
    assertThat(countInstallCodes(envId)).isZero();
  }

  /** 测试意图：再次签发安装 code（saveInstallCode upsert）会原子替换旧凭证，旧 code 无法再消费。 */
  @Test
  void saveInstallCodeUpsertReplacesPreviousCode() {
    UUID envId = insertEnvironment("env-gamma", "token-gamma");
    String tokenHash = RegistrationTokens.hash("token-gamma");
    String oldCodeHash = EnvironmentInstallCodes.hash("old-code");
    String newCodeHash = EnvironmentInstallCodes.hash("new-code");
    Instant now = Instant.parse("2026-10-23T10:00:00.000Z");

    repository.saveInstallCode(envId, oldCodeHash, tokenHash, now.plus(5, ChronoUnit.MINUTES));
    repository.saveInstallCode(envId, newCodeHash, tokenHash, now.plus(10, ChronoUnit.MINUTES));

    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    // 旧 code 消费失败
    boolean oldConsumed = repository.consumeInstallCode(envId, oldCodeHash, tokenHash, now);
    assertThat(oldConsumed).isFalse();
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    // 新 code 消费成功
    boolean newConsumed = repository.consumeInstallCode(envId, newCodeHash, tokenHash, now);
    assertThat(newConsumed).isTrue();
    assertThat(countInstallCodes(envId)).isZero();
  }

  /** 测试意图：环境 ID、codeHash 或 registrationTokenHash 任意一项不匹配时均拒绝消费且不破坏已有记录。 */
  @Test
  void consumeWithMismatchedCredentialsFailsAndPreservesCode() {
    UUID envId = insertEnvironment("env-delta", "token-delta");
    UUID anotherEnvId = insertEnvironment("env-other", "token-other");
    String tokenHash = RegistrationTokens.hash("token-delta");
    String codeHash = EnvironmentInstallCodes.hash("target-code");
    String wrongCodeHash = EnvironmentInstallCodes.hash("wrong-code");
    String wrongTokenHash = RegistrationTokens.hash("wrong-token");
    Instant now = Instant.parse("2026-10-23T10:00:00.000Z");

    repository.saveInstallCode(envId, codeHash, tokenHash, now.plus(10, ChronoUnit.MINUTES));

    // 错误 Environment ID
    assertThat(repository.consumeInstallCode(anotherEnvId, codeHash, tokenHash, now)).isFalse();
    // 错误 codeHash
    assertThat(repository.consumeInstallCode(envId, wrongCodeHash, tokenHash, now)).isFalse();
    // 错误 tokenHash
    assertThat(repository.consumeInstallCode(envId, codeHash, wrongTokenHash, now)).isFalse();

    // 记录完好保留，正确凭据消费成功
    assertThat(countInstallCodes(envId)).isEqualTo(1L);
    assertThat(repository.consumeInstallCode(envId, codeHash, tokenHash, now)).isTrue();
    assertThat(countInstallCodes(envId)).isZero();
  }

  /** 测试意图：Environment 删除时，关联的临时安装 code 必须由外键 ON DELETE CASCADE 自动清理。 */
  @Test
  void deleteEnvironmentCascadesToInstallCode() {
    UUID envId = insertEnvironment("env-epsilon", "token-epsilon");
    String tokenHash = RegistrationTokens.hash("token-epsilon");
    String codeHash = EnvironmentInstallCodes.hash("cascade-code");
    Instant now = Instant.parse("2026-10-23T10:00:00.000Z");

    repository.saveInstallCode(envId, codeHash, tokenHash, now.plus(10, ChronoUnit.MINUTES));
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    // 删除 Environment
    jdbcClient.sql("delete from environment where id = :id").param("id", envId).update();

    assertThat(countInstallCodes(envId)).isZero();
    assertThat(repository.consumeInstallCode(envId, codeHash, tokenHash, now)).isFalse();
  }

  private UUID insertEnvironment(String name, String token) {
    UUID id = UUID.randomUUID();
    jdbcClient
        .sql(
            """
            insert into environment (
                id, name, registration_token_hash, install_config, created_at, updated_at, version
            ) values (
                :id, :name, :registrationTokenHash, '{}'::jsonb, current_timestamp, current_timestamp, 0
            )
            """)
        .param("id", id)
        .param("name", name)
        .param("registrationTokenHash", RegistrationTokens.hash(token))
        .update();
    return id;
  }

  private long countInstallCodes(UUID environmentId) {
    return jdbcClient
        .sql("select count(*) from environment_install_code where environment_id = :id")
        .param("id", environmentId)
        .query(Long.class)
        .single();
  }
}

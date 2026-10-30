package fun.fengwk.kkstudio.platform.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * {@link EnvironmentRepository} 安装授权在真实 PostgreSQL 17 上的集成测试。
 *
 * <p>验证目标包括：code 只落在 environment.install_code 且签发不推进版本、一次性原子兑换、数据库时间下的过期拒绝、摘要/版本不匹配安全性，以及兑换同时轮换
 * token 并递增版本。
 */
class EnvironmentRepositoryInstallCodeIntegrationTest {

  private static DataSource dataSource;
  private static JdbcClient jdbcClient;
  private static TransactionTemplate transaction;

  private EnvironmentRepository repository;

  @BeforeAll
  static void beforeAll() {
    dataSource =
        new SimpleDriverDataSource(
            new Driver(),
            PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
            PostgresSchemaSupport.POSTGRES.getUsername(),
            PostgresSchemaSupport.POSTGRES.getPassword());
    jdbcClient = JdbcClient.create(dataSource);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    repository = new EnvironmentRepository(jdbcClient, mock(EnvironmentChangeNotifier.class));
  }

  /** 测试意图：签发把严格对象写到 environment 行且不推进 CAS 版本；再次签发覆盖旧摘要。 */
  @Test
  void saveWritesOneObjectWithoutAdvancingVersion() {
    UUID envId = insertEnvironment("env-alpha", "token-alpha");
    String firstHash = EnvironmentInstallCodes.hash("first-code");
    String secondHash = EnvironmentInstallCodes.hash("second-code");
    Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    assertThat(save(envId, 0L, firstHash, expiresAt)).isTrue();
    assertThat(version(envId)).isZero();
    assertThat(installCodeHash(envId)).isEqualTo(firstHash);

    assertThat(save(envId, 0L, secondHash, expiresAt.plusSeconds(60))).isTrue();
    assertThat(version(envId)).isZero();
    assertThat(installCodeHash(envId)).isEqualTo(secondHash);
  }

  /** 测试意图：成功兑换原子清空 code、轮换 token 摘要并递增版本，同一 code 不能二次兑换。 */
  @Test
  void redeemOnceClearsCodeRotatesTokenAndAdvancesVersion() {
    UUID envId = insertEnvironment("env-beta", "token-beta");
    String codeHash = EnvironmentInstallCodes.hash("one-shot-code");
    String newTokenHash = RegistrationTokens.hash("rotated-token");
    assertThat(save(envId, 0L, codeHash, Instant.now().plus(10, ChronoUnit.MINUTES))).isTrue();

    assertThat(redeem(envId, 0L, codeHash, newTokenHash)).isTrue();
    assertThat(installCode(envId)).isNull();
    assertThat(registrationTokenHash(envId)).isEqualTo(newTokenHash);
    assertThat(version(envId)).isEqualTo(1L);

    assertThat(redeem(envId, 1L, codeHash, RegistrationTokens.hash("second-token"))).isFalse();
    assertThat(registrationTokenHash(envId)).isEqualTo(newTokenHash);
    assertThat(version(envId)).isEqualTo(1L);
  }

  /** 测试意图：数据库时间下已过期的 code 被拒绝，且 code、token 与版本都保持不变。 */
  @Test
  void expiredCodeIsRejectedAndPreserved() {
    UUID envId = insertEnvironment("env-gamma", "token-gamma");
    String codeHash = EnvironmentInstallCodes.hash("expired-code");
    assertThat(save(envId, 0L, codeHash, Instant.now().minus(1, ChronoUnit.HOURS))).isTrue();

    assertThat(redeem(envId, 0L, codeHash, RegistrationTokens.hash("unused-token"))).isFalse();
    assertThat(installCodeHash(envId)).isEqualTo(codeHash);
    assertThat(registrationTokenHash(envId)).isEqualTo(RegistrationTokens.hash("token-gamma"));
    assertThat(version(envId)).isZero();
  }

  /** 测试意图：摘要或预期版本不匹配时拒绝且保留 code，正确摘要和版本仍可兑换。 */
  @Test
  void mismatchedHashOrVersionRejectedAndPreservesCode() {
    UUID envId = insertEnvironment("env-delta", "token-delta");
    String codeHash = EnvironmentInstallCodes.hash("target-code");
    assertThat(save(envId, 0L, codeHash, Instant.now().plus(10, ChronoUnit.MINUTES))).isTrue();

    assertThat(redeem(envId, 0L, EnvironmentInstallCodes.hash("wrong-code"), tokenHash("wrong")))
        .isFalse();
    assertThat(redeem(envId, 1L, codeHash, tokenHash("stale-version"))).isFalse();
    assertThat(installCodeHash(envId)).isEqualTo(codeHash);
    assertThat(version(envId)).isZero();

    assertThat(redeem(envId, 0L, codeHash, tokenHash("correct"))).isTrue();
    assertThat(installCode(envId)).isNull();
  }

  /** 测试意图：签发使用过期 CAS 版本时不得覆盖当前 code。 */
  @Test
  void saveWithStaleVersionDoesNotWrite() {
    UUID envId = insertEnvironment("env-epsilon", "token-epsilon");
    assertThat(save(envId, 1L, EnvironmentInstallCodes.hash("stale-issue"), Instant.now()))
        .isFalse();
    assertThat(installCode(envId)).isNull();
  }

  /** 测试意图：安装 code 写路径与稳定行写路径一样必须处于外层事务。 */
  @Test
  void saveRequiresOuterTransaction() {
    UUID envId = insertEnvironment("env-zeta", "token-zeta");
    assertThatThrownBy(
            () ->
                repository.saveInstallCode(
                    envId,
                    0L,
                    EnvironmentInstallCodes.hash("outside-tx"),
                    Instant.now().plus(5, ChronoUnit.MINUTES)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(installCode(envId)).isNull();
  }

  private boolean save(UUID envId, long expectedVersion, String codeHash, Instant expiresAt) {
    return Boolean.TRUE.equals(
        transaction.execute(
            status -> repository.saveInstallCode(envId, expectedVersion, codeHash, expiresAt)));
  }

  private boolean redeem(
      UUID envId, long expectedVersion, String codeHash, String registrationTokenHash) {
    return Boolean.TRUE.equals(
        transaction.execute(
            status ->
                repository.redeemInstallCode(
                    envId, expectedVersion, codeHash, registrationTokenHash)));
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

  private String installCodeHash(UUID environmentId) {
    return jdbcClient
        .sql("select install_code->>'codeHash' from environment where id = :id")
        .param("id", environmentId)
        .query(String.class)
        .single();
  }

  private String installCode(UUID environmentId) {
    return jdbcClient
        .sql("select install_code::text from environment where id = :id")
        .param("id", environmentId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private String registrationTokenHash(UUID environmentId) {
    return jdbcClient
        .sql("select registration_token_hash from environment where id = :id")
        .param("id", environmentId)
        .query(String.class)
        .single();
  }

  private long version(UUID environmentId) {
    return jdbcClient
        .sql("select version from environment where id = :id")
        .param("id", environmentId)
        .query(Long.class)
        .single();
  }

  private static String tokenHash(String token) {
    return RegistrationTokens.hash(token);
  }
}

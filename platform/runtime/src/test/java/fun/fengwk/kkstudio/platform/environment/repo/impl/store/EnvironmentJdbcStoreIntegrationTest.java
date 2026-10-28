package fun.fengwk.kkstudio.platform.environment.repo.impl.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.platform.environment.RegistrationTokens;
import fun.fengwk.kkstudio.platform.environment.repo.impl.model.EnvironmentDO;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentInstallCodes;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** {@code environment} 读取路径与两种行锁的真实 SQL 覆盖：按名/按注册令牌查询、排序与 KEY SHARE 读取。 */
class EnvironmentJdbcStoreIntegrationTest {

  private static JdbcClient jdbcClient;

  private EnvironmentJdbcStore store;

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
    store = new EnvironmentJdbcStore(jdbcClient);
  }

  /** listAll 按 name 升序；按主键/名称/注册令牌的读取与两种行锁返回权威行。 */
  @Test
  void readPathsAndLocksReturnAuthoritativeRows() {
    UUID alpha = insert("alpha", "token-alpha");
    UUID beta = insert("beta", "token-beta");

    assertThat(store.getById(alpha).getName()).isEqualTo("alpha");
    assertThat(store.getByName("beta").getId()).isEqualTo(beta);
    assertThat(store.getByRegistrationTokenHash(RegistrationTokens.hash("token-alpha")).getId())
        .isEqualTo(alpha);
    assertThat(store.lockById(beta).getName()).isEqualTo("beta");
    assertThat(store.lockForKeyShare(alpha).getName()).isEqualTo("alpha");
    assertThat(store.existsByName("alpha")).isTrue();
    assertThat(store.existsByName("missing")).isFalse();
    assertThat(store.listAll()).extracting(EnvironmentDO::getName).containsExactly("alpha", "beta");
  }

  /** listNewestFirst 按 updated_at desc、created_at desc、name asc 排序。 */
  @Test
  void listNewestFirstOrdersByUpdatedThenCreatedThenName() {
    UUID alpha = insert("alpha", "token-alpha");
    insert("beta", "token-beta");
    // 把 alpha 的时间戳整体推早，消除同一毫秒并列，得到确定顺序。
    jdbcClient
        .sql(
            "update environment set updated_at = updated_at - interval '1 hour',"
                + " created_at = created_at - interval '1 hour' where id = :id")
        .param("id", alpha)
        .update();

    assertThat(store.listNewestFirst())
        .extracting(EnvironmentDO::getName)
        .containsExactly("beta", "alpha");
  }

  /** 安装 code 与稳定行同表：仅 token 变化时清空，配置更新保留未核销 code。 */
  @Test
  void updateByIdClearsInstallCodeOnlyWhenRegistrationTokenChanges() {
    UUID id = insert("gamma", "token-gamma");
    setInstallCode(id, EnvironmentInstallCodes.hash("install-code"));
    EnvironmentDO configUpdate = store.getById(id);
    configUpdate.setInstallConfigJson("{\"operatingSystem\":\"macos\"}");

    assertThat(store.updateById(configUpdate, configUpdate.getVersion())).isEqualTo(1);
    assertThat(readInstallCode(id)).isNotNull();

    EnvironmentDO tokenUpdate = store.getById(id);
    tokenUpdate.setRegistrationTokenHash(RegistrationTokens.hash("rotated-token"));
    assertThat(store.updateById(tokenUpdate, tokenUpdate.getVersion())).isEqualTo(1);
    assertThat(readInstallCode(id)).isNull();
  }

  private void setInstallCode(UUID id, String codeHash) {
    jdbcClient
        .sql(
            "update environment set install_code = jsonb_build_object("
                + "'codeHash', :codeHash, 'expiresAt', :expiresAt) where id = :id")
        .param("codeHash", codeHash)
        .param("expiresAt", Instant.now().plusSeconds(300).toString())
        .param("id", id)
        .update();
  }

  private String readInstallCode(UUID id) {
    return jdbcClient
        .sql("select install_code::text from environment where id = :id")
        .param("id", id)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private UUID insert(String name, String token) {
    EnvironmentDO row = new EnvironmentDO();
    row.setId(UUID.randomUUID());
    row.setName(name);
    row.setRegistrationTokenHash(RegistrationTokens.hash(token));
    row.setInstallConfigJson("{}");
    assertThat(store.insert(row)).isEqualTo(1);
    return row.getId();
  }
}

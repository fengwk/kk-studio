package fun.fengwk.kkstudio.platform.environment.repo.impl.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.platform.environment.repo.impl.model.EnvironmentDO;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
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
    assertThat(store.getByRegistrationToken("token-alpha").getId()).isEqualTo(alpha);
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

  private UUID insert(String name, String token) {
    EnvironmentDO row = new EnvironmentDO();
    row.setId(UUID.randomUUID());
    row.setName(name);
    row.setRegistrationToken(token);
    row.setInstallConfigJson("{}");
    assertThat(store.insert(row)).isEqualTo(1);
    return row.getId();
  }
}

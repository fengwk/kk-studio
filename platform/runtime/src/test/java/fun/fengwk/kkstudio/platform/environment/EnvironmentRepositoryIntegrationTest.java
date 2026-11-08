package fun.fengwk.kkstudio.platform.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigDTO;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** {@code environment} 读取路径与两种行锁的真实 SQL 覆盖：按名/按注册令牌查询、排序与 KEY SHARE 读取。 */
class EnvironmentRepositoryIntegrationTest {

  private static JdbcClient jdbcClient;
  private static TransactionTemplate transaction;

  private EnvironmentRepository repository;

  @BeforeAll
  static void beforeAll() {
    DataSource dataSource =
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
    repository = new EnvironmentRepository(jdbcClient, new EnvironmentChangeNotifier(jdbcClient));
  }

  /** listAll 按 name 升序；按主键/名称/注册令牌的读取与两种行锁返回权威行。 */
  @Test
  void readPathsAndLocksReturnAuthoritativeRows() {
    UUID alpha = insert("alpha", "token-alpha");
    UUID beta = insert("beta", "token-beta");

    assertThat(repository.getById(alpha).getName()).isEqualTo("alpha");
    assertThat(repository.getById(alpha).getDaemonConfig().getStudioUrl())
        .isEqualTo("https://studio.example.com");
    assertThat(repository.getByName("beta").getId()).isEqualTo(beta);
    assertThat(
            repository.getByRegistrationTokenHash(RegistrationTokens.hash("token-alpha")).getId())
        .isEqualTo(alpha);
    assertThat(repository.lockById(beta).getName()).isEqualTo("beta");
    assertThat(repository.lockForKeyShare(alpha).getName()).isEqualTo("alpha");
    assertThat(repository.existsByName("alpha")).isTrue();
    assertThat(repository.existsByName("missing")).isFalse();
    assertThat(repository.listAll())
        .extracting(Environment::getName)
        .containsExactly("alpha", "beta");
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

    assertThat(repository.listNewestFirst())
        .extracting(Environment::getName)
        .containsExactly("beta", "alpha");
  }

  /** 只有备注变化时保留未兑换安装码；完整配置或 token 变化时同一 CAS 语句撤销安装码。 */
  @Test
  void updateByIdClearsInstallCodeOnlyWhenConfigOrTokenChanges() {
    UUID id = insert("gamma", "token-gamma");
    setInstallCode(id, EnvironmentInstallCodes.hash("install-code"));

    Environment noteUpdate = repository.getById(id);
    noteUpdate.setNote("new note");
    assertTrue(
        Boolean.TRUE.equals(transaction.execute(status -> repository.updateById(noteUpdate, 0L))));
    assertThat(readInstallCode(id)).isEqualTo(EnvironmentInstallCodes.hash("install-code"));

    setInstallCode(id, EnvironmentInstallCodes.hash("install-code"));
    Environment configUpdate = repository.getById(id);
    DaemonConfigDTO config = configUpdate.getDaemonConfig();
    config.setBashExecutable("/bin/bash");
    configUpdate.setDaemonConfig(config);
    assertTrue(
        Boolean.TRUE.equals(
            transaction.execute(status -> repository.updateById(configUpdate, 1L))));
    assertThat(readInstallCode(id)).isNull();
    assertThat(repository.getById(id).getDaemonConfig().getBashExecutable()).isEqualTo("/bin/bash");

    setInstallCode(id, EnvironmentInstallCodes.hash("install-code"));
    Environment tokenUpdate = repository.getById(id);
    tokenUpdate.setRegistrationTokenHash(RegistrationTokens.hash("rotated-token"));
    assertTrue(
        Boolean.TRUE.equals(transaction.execute(status -> repository.updateById(tokenUpdate, 2L))));
    assertThat(readInstallCode(id)).isNull();
    assertThat(repository.getById(id).getVersion()).isEqualTo(3L);
  }

  private void setInstallCode(UUID id, String codeHash) {
    jdbcClient
        .sql(
            "update environment set install_code_hash = :codeHash,"
                + " install_code_expires_at = current_timestamp + interval '5 minutes'"
                + " where id = :id")
        .param("codeHash", codeHash)
        .param("id", id)
        .update();
  }

  private String readInstallCode(UUID id) {
    return jdbcClient
        .sql(
            "select install_code_hash from environment where id = :id"
                + " and install_code_expires_at > current_timestamp")
        .param("id", id)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private UUID insert(String name, String token) {
    UUID id = UUID.randomUUID();
    jdbcClient
        .sql(
            """
            insert into environment (
                id, name, note, registration_token_hash, daemon_config, created_at, updated_at, version
            ) values (
                :id, :name, null, :registrationTokenHash,
                '{"studioUrl":"https://studio.example.com","bashExecutable":null,"lsp":null,"proxyUrl":null}'::jsonb,
                current_timestamp, current_timestamp, 0
            )
            """)
        .param("id", id)
        .param("name", name)
        .param("registrationTokenHash", RegistrationTokens.hash(token))
        .update();
    return id;
  }
}

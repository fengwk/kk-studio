package fun.fengwk.kkstudio.platform.catalog.definition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** {@code agent} 读取路径的真实 SQL 覆盖：listAll 排序、count 与 jsonb 引用检查。 */
class AgentRepositoryIntegrationTest {

  private static JdbcClient jdbcClient;

  private AgentRepository store;

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
    store = new AgentRepository(jdbcClient);
  }

  /** listAll 按 name 升序列出全部行。 */
  @Test
  void listAllOrdersByName() {
    insert("alpha", "{\"subagents\":[\"beta\"]}");
    insert("beta", "{}");

    assertThat(store.listAll()).extracting(Agent::name).containsExactly("alpha", "beta");
  }

  /** existsReferencingSubagent 与 existsReferencingSkill 按 config jsonb 形状判定。 */
  @Test
  void referenceChecksMatchJsonbShape() {
    insert(
        "parent",
        "{\"subagents\":[\"target\"],\"skills\":[{\"packageName\":\"pkg\",\"name\":\"skill\"}]}");
    insert("target", "{}");

    assertThat(store.existsReferencingSubagent("target")).isTrue();
    assertThat(store.existsReferencingSubagent("parent")).isFalse();
    assertThat(store.existsReferencingSkill("pkg", "skill")).isTrue();
    assertThat(store.existsReferencingSkill("pkg", "other")).isFalse();
  }

  private void insert(String name, String configJson) {
    long id = System.nanoTime();
    String providerName = "prov-" + id;
    String modelName = "mod-" + id;
    jdbcClient
        .sql(
            "insert into provider (name, provider_type, config, connection_generation_id)"
                + " values (:name, 'openai', '{}'::jsonb, :generation)")
        .param("name", providerName)
        .param("generation", UUID.randomUUID())
        .update();
    jdbcClient
        .sql(
            "insert into model (name, kind, provider_name, model_id, config)"
                + " values (:model, 'MODEL', :provider, :modelId, '{}'::jsonb)")
        .param("provider", providerName)
        .param("model", modelName)
        .param("modelId", "wire-" + id)
        .update();

    assertThat(
            store.create(
                Agent.builder()
                    .name(name)
                    .type(AgentType.USER)
                    .execution(BuiltinExecution.of(ModelSelection.model(modelName, null)))
                    .configJson(configJson)
                    .build()))
        .isTrue();
  }
}

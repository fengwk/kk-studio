package fun.fengwk.kkstudio.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.common.page.Page;
import fun.fengwk.kkstudio.common.page.PageQuery;
import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** 目录仓库的分页结果与总数必须来自同一条语句；存在性检查不依赖 count。 */
class CatalogPaginationIntegrationTest {

  private JdbcClient fixture;
  private JdbcClient observed;

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection connection = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(connection);
      PostgresSchemaSupport.applyBaseline(connection);
    }
    SimpleDriverDataSource dataSource =
        new SimpleDriverDataSource(
            new Driver(),
            PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
            PostgresSchemaSupport.POSTGRES.getUsername(),
            PostgresSchemaSupport.POSTGRES.getPassword());
    fixture = JdbcClient.create(dataSource);
    observed = spy(JdbcClient.create(dataSource));
  }

  @Test
  void agentModelPageReturnsResultsAndTotalFromOneStatement() {
    insertProvider("provider");
    insertModel("delta", "provider");
    insertModel("alpha", "provider");
    insertModel("charlie", "provider");
    insertModel("bravo", "provider");
    AgentModelRepository repository = new AgentModelRepository(observed);

    assertModelPage(repository.page(new PageQuery(1, 3)), List.of("alpha", "bravo", "charlie"), 4);
    verify(observed, times(1)).sql(anyString());

    reset(observed);
    assertModelPage(repository.page(new PageQuery(2, 3)), List.of("delta"), 4);
    verify(observed, times(1)).sql(anyString());

    reset(observed);
    assertModelPage(repository.page(new PageQuery(3, 3)), List.of(), 4);
    verify(observed, times(1)).sql(anyString());
  }

  @Test
  void agentProviderPageReturnsResultsAndTotalFromOneStatement() {
    insertProvider("delta");
    insertProvider("alpha");
    insertProvider("charlie");
    insertProvider("bravo");
    AgentProviderRepository repository = new AgentProviderRepository(observed);

    assertProviderPage(
        repository.page(new PageQuery(1, 3)), List.of("alpha", "bravo", "charlie"), 4);
    verify(observed, times(1)).sql(anyString());

    reset(observed);
    assertProviderPage(repository.page(new PageQuery(2, 3)), List.of("delta"), 4);
    verify(observed, times(1)).sql(anyString());

    reset(observed);
    assertProviderPage(repository.page(new PageQuery(3, 3)), List.of(), 4);
    verify(observed, times(1)).sql(anyString());
  }

  @Test
  void emptyCatalogPagesStillReportZeroTotalInOneStatement() {
    assertModelPage(new AgentModelRepository(observed).page(new PageQuery(1, 2)), List.of(), 0);
    verify(observed, times(1)).sql(anyString());

    reset(observed);
    assertProviderPage(
        new AgentProviderRepository(observed).page(new PageQuery(1, 2)), List.of(), 0);
    verify(observed, times(1)).sql(anyString());
  }

  @Test
  void existenceChecksMatchJsonbReferencesWithoutCounting() {
    insertProvider("provider");
    insertProvider("unused-provider");
    insertModel("member", "provider");
    insertGroup("group", "member", "default");
    insertDefinition("builtin-agent", "member", "fast", "builtin");
    insertDefinition("other-agent", "member", "fast", "other");
    AgentProviderRepository providers = new AgentProviderRepository(observed);
    AgentModelRepository models = new AgentModelRepository(observed);

    assertTrue(providers.hasModels("provider"));
    assertFalse(providers.hasModels("unused-provider"));
    assertTrue(models.hasGroupsReferencing("member"));
    assertFalse(models.hasGroupsReferencing("unknown"));
    assertTrue(models.hasVariantReferences("member", "default"));
    assertTrue(models.hasVariantReferences("member", "fast"));
    assertFalse(models.hasVariantReferences("member", "other"));
    assertTrue(models.hasAgents("member"));
    assertFalse(models.hasAgents("unknown"));
  }

  private void assertModelPage(Page<AgentModel> page, List<String> names, long total) {
    assertEquals(total, page.getTotalCount());
    assertEquals(names, page.getResults().stream().map(AgentModel::name).toList());
  }

  private void assertProviderPage(Page<AgentProvider> page, List<String> names, long total) {
    assertEquals(total, page.getTotalCount());
    assertEquals(names, page.getResults().stream().map(AgentProvider::name).toList());
  }

  private void assertDefinitionPage(Page<Agent> page, List<String> names, long total) {
    assertEquals(total, page.getTotalCount());
    assertEquals(names, page.getResults().stream().map(Agent::name).toList());
  }

  private void insertProvider(String name) {
    fixture
        .sql(
            """
            insert into agent_provider (name, provider_type, config, connection_generation_id)
            values (:name, 'openai', '{}'::jsonb, :generation)
            """)
        .param("name", name)
        .param("generation", UUID.randomUUID())
        .update();
  }

  private void insertModel(String name, String providerName) {
    fixture
        .sql(
            """
            insert into agent_model (name, kind, provider_name, model_id, config)
            values (:name, 'MODEL', :providerName, :modelId, '{}'::jsonb)
            """)
        .param("name", name)
        .param("providerName", providerName)
        .param("modelId", "wire-" + name)
        .update();
  }

  private void insertGroup(String name, String memberName, String variant) {
    fixture
        .sql(
            """
            insert into agent_model (name, kind, group_config)
            values (:name, 'GROUP',
                    jsonb_build_object('members',
                        jsonb_build_array(jsonb_build_object(
                            'modelName', :memberName, 'variant', :variant))))
            """)
        .param("name", name)
        .param("memberName", memberName)
        .param("variant", variant)
        .update();
  }

  private void insertDefinition(String name) {
    fixture
        .sql(
            """
            insert into agent (name, type, execution, config)
            values (:name, 'BUILTIN', null, '{}'::jsonb)
            """)
        .param("name", name)
        .update();
  }

  private void insertDefinition(String name, String modelName, String variant, String driverName) {
    fixture
        .sql(
            """
            insert into agent (name, type, execution, config)
            values (:name, 'BUILTIN',
                    jsonb_build_object('driver', :driver,
                        'settings', jsonb_build_object(
                            'modelName', :modelName, 'variant', :variant)),
                    '{}'::jsonb)
            """)
        .param("name", name)
        .param("driver", driverName)
        .param("modelName", modelName)
        .param("variant", variant)
        .update();
  }
}

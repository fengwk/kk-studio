package fun.fengwk.kkstudio.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** 目录仓库的存在性检查直接按 JSONB 引用判断，不依赖 count 查询。 */
class CatalogReferenceQueriesIntegrationTest {

  private JdbcClient fixture;

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
  }

  @Test
  void existenceChecksMatchJsonbReferencesWithoutCounting() {
    insertProvider("provider");
    insertProvider("unused-provider");
    insertModel("member", "provider");
    insertGroup("group", "member", "default");
    insertDefinition("builtin-agent", "member", "fast", "builtin");
    insertDefinition("other-agent", "member", "fast", "other");
    ProviderRepository providers = new ProviderRepository(fixture);
    ModelRepository models = new ModelRepository(fixture);

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

  private void insertProvider(String name) {
    fixture
        .sql(
            """
            insert into provider (name, provider_type, config, connection_generation_id)
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
            insert into model (name, kind, provider_name, model_id, config)
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
            insert into model (name, kind, group_config)
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

package fun.fengwk.kkstudio.platform.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.Driver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.PostgresqlAgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.store.AgentDefinitionJdbcStore;
import fun.fengwk.kkstudio.platform.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.service.AgentModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginCredentialJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.plugin.service.PluginConfigurationCommitService;
import fun.fengwk.kkstudio.platform.plugin.service.PluginContextFactory;
import fun.fengwk.kkstudio.platform.plugin.service.PluginSecretPatch;
import fun.fengwk.kkstudio.platform.plugin.testing.TestPluginFixtures;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderEditablePropertiesDTO;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 配置同步「插件普通配置导入」的真实 PostgreSQL 契约测试。
 *
 * <p>只在这里对真实库、真实事务管理器与真实提交服务成立：
 *
 * <ol>
 *   <li>计划到写入之间被删除的依赖 Agent 不会改变插件目录 epoch，必须由事务内的依赖重检发现；此时此前写入的 provider 行、插件配置历史与 catalog
 *       全部回滚，且没有任何 afterCommit 通知。
 *   <li>普通配置导入（秘密补丁恒为空）承载的上下文变化会合法推进认证代际并按新上下文重写密文，但保持同一份秘密载荷：旧 scoped
 *       引用被围栏拒绝，新引用取回同一合成载荷。通知只在提交后触发，事务内不可观测。
 * </ol>
 *
 * <p>合成 StudioPlugin 只用公共 API 声明普通值、秘密与依赖，不引用任何具体插件实现，也不访问网络或真实凭据。
 */
class ConfigSyncPluginImportPostgresIntegrationTest {

  private static final String DEPENDENT_PLUGIN_ID = "configsync.dep.plugin";
  private static final String STATIC_PLUGIN_ID = "configsync.static.plugin";
  private static final String DEPENDENCY_AGENT = "configsync-dep-agent";
  private static final String SYNTHETIC_SECRET = "synthetic-api-key-marker-24680";
  private static final String PROVIDER_NAME = "configsync-provider";
  private static final String MODEL_PROVIDER = "configsync-model-provider";
  private static final String MODEL_NAME = "configsync-model";

  /** 已补齐默认值的规范普通值文本：与 schema.resolveValues 输出恒等，可直接作为权威历史值。 */
  private static final String VALUES_V1 =
      "{\"endpoint\":\"https://v1.example.com\",\"label\":\"default\"}";

  private static final String VALUES_V2 =
      "{\"endpoint\":\"https://v2.example.com\",\"label\":\"default\"}";

  private static DataSource dataSource;
  private static JdbcClient jdbcClient;

  private final Instant now = Instant.parse("2026-10-01T12:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private final PluginCredentialCodec codec = new PluginCredentialCodec();
  private final AtomicInteger dispatcherWakes = new AtomicInteger();
  private final AtomicInteger catalogWakes = new AtomicInteger();

  @TempDir Path tempDir;

  private StudioPluginRegistry registry;
  private PluginAgentDependencies pluginAgentDependencies;
  private PlatformTransactionManager transactionManager;
  private PluginConfigurationRepository configurationRepository;
  private PluginCredentialRepository credentialRepository;
  private DatabasePluginCredentialStore credentialStore;
  private PluginConfigurationCommitService commitService;
  private AgentDefinitionRepository definitionRepository;
  private ConfigSyncApplier applier;
  private AgentProviderService providerService;

  @BeforeAll
  static void beforeAll() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    dataSource =
        new SimpleDriverDataSource(
            new Driver(),
            PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
            PostgresSchemaSupport.POSTGRES.getUsername(),
            PostgresSchemaSupport.POSTGRES.getPassword());
    jdbcClient = JdbcClient.create(dataSource);
  }

  @BeforeEach
  void setUp() throws Exception {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    dispatcherWakes.set(0);
    catalogWakes.set(0);
    registry =
        ConfigSyncFixtures.registry(
            new ConfigSyncFixtures.AgentDependencyPlugin(DEPENDENT_PLUGIN_ID),
            new StaticKeyPlugin());
    transactionManager = new DataSourceTransactionManager(dataSource);
    configurationRepository =
        new PostgresqlPluginConfigurationRepository(
            new PluginConfigurationJdbcStore(jdbcClient),
            new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(dataSource)));
    credentialRepository =
        new PostgresqlPluginCredentialRepository(new PluginCredentialJdbcStore(jdbcClient));
    definitionRepository =
        new PostgresqlAgentDefinitionRepository(new AgentDefinitionJdbcStore(jdbcClient));

    Path keyFile = tempDir.resolve("configsync-import.key");
    Files.write(keyFile, "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    PluginCredentialKeyLoader keyLoader =
        new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());
    credentialStore =
        new DatabasePluginCredentialStore(
            credentialRepository, configurationRepository, codec, keyLoader, clock);
    pluginAgentDependencies =
        new PluginAgentDependencies(registry, configurationRepository, definitionRepository);
    commitService =
        new PluginConfigurationCommitService(
            registry,
            configurationRepository,
            credentialRepository,
            codec,
            keyLoader,
            new PluginContextFactory(credentialStore, () -> null, () -> null, () -> null),
            List::of,
            List::of,
            transactionManager,
            clock,
            dispatcherWakes::incrementAndGet,
            catalogWakes::incrementAndGet,
            pluginAgentDependencies);

    // provider 服务是唯一真实库写入的替身：只在同一事务内真插一行 provider，用于证明它随插件失败一起回滚。
    providerService = mock(AgentProviderService.class);
    when(providerService.importProvider(any(), any()))
        .thenAnswer(
            invocation -> {
              jdbcClient
                  .sql(
                      "insert into agent_provider"
                          + " (name, provider_type, config, connection_generation_id)"
                          + " values (:name, 'openai', '{}'::jsonb, cast(:generation as uuid))")
                  .param("name", invocation.getArgument(0, String.class))
                  .param("generation", UUID.randomUUID().toString())
                  .update();
              return null;
            });

    applier =
        new ConfigSyncApplier(
            providerService,
            mock(AgentModelRepository.class),
            mock(AgentModelService.class),
            mock(SkillCatalogService.class),
            mock(McpServerService.class),
            definitionRepository,
            mock(AgentDefinitionService.class),
            mock(EnvironmentRepository.class),
            mock(EnvironmentService.class),
            mock(SystemSettingsService.class),
            commitService,
            pluginAgentDependencies);
  }

  /** 计划形成后依赖 Agent 消失：事务内重检必须让此前写入与插件目录一起回滚，且不触发任何提交后回调。 */
  @Test
  void deletedDependencyRollsBackProvidersPluginsAndCatalog() {
    seedCatalog();

    seedAgentModel();
    assertTrue(definitionRepository.create(agentDefinition(DEPENDENCY_AGENT)));
    ConfigSyncPlan plan =
        plan(
            List.of(providerSpec(PROVIDER_NAME)),
            List.of(
                new ConfigSyncPlan.PluginImport(
                    DEPENDENT_PLUGIN_ID, 1L, "{\"promptAgentName\":\"" + DEPENDENCY_AGENT + "\"}")),
            1L);

    // 计划与写入之间删除依赖：插件目录 epoch 不变，CAS 无法发现它。
    assertTrue(definitionRepository.deleteByName(DEPENDENCY_AGENT, 0L));

    AiValidationException error =
        assertThrows(AiValidationException.class, () -> applyInTransaction(plan));

    assertEquals("plugins reference a missing agent", error.getMessage());
    assertEquals(null, error.getCause(), "the recheck must not leak a cause");
    assertEquals(
        0L, count("select count(*) from agent_provider where name = '" + PROVIDER_NAME + "'"));
    assertEquals(1L, configurationRepository.readCatalog().epoch());
    assertEquals(1L, pluginHistoryCount(DEPENDENT_PLUGIN_ID));
    assertEquals(0, dispatcherWakes.get());
    assertEquals(0, catalogWakes.get(), "a rolled back import must not notify the catalog");
  }

  /** 普通导入的上下文变化推进代际并按新上下文重写密文，但保持同一合成秘密载荷；通知只在提交后发生。 */
  @Test
  void ordinaryImportKeepsTheSyntheticSecretAndFencesTheOldContext() {
    seedCatalog();
    // 与生产一致：首次 SET 在事务内提交，提交后通知可确定观测。
    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              commitService.configure(
                  STATIC_PLUGIN_ID,
                  1L,
                  1L,
                  VALUES_V1,
                  Map.of("apiKey", PluginSecretPatch.set(SYNTHETIC_SECRET)));
              return null;
            });
    int catalogWakesBeforeImport = catalogWakes.get();
    int dispatcherWakesBeforeImport = dispatcherWakes.get();
    PluginCredentialRow seeded = credentialRepository.find(STATIC_PLUGIN_ID).orElseThrow();
    PluginCredentialReference oldReference =
        new PluginCredentialReference(
            STATIC_PLUGIN_ID, seeded.authGeneration(), seeded.contextFingerprint());
    assertEquals(
        "{\"apiKey\":\"" + SYNTHETIC_SECRET + "\"}",
        credentialStore.resolve(oldReference).payloadJson());

    // 导入只带普通值（无秘密字段）且秘密补丁恒为空：不得复制、恢复或清除任何凭据。
    ConfigSyncPlan plan =
        plan(
            List.of(),
            List.of(new ConfigSyncPlan.PluginImport(STATIC_PLUGIN_ID, 2L, VALUES_V2)),
            2L);
    AtomicBoolean notifiedInsideTransaction = new AtomicBoolean();
    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              applier.apply(plan);
              notifiedInsideTransaction.set(
                  dispatcherWakes.get() > dispatcherWakesBeforeImport
                      || catalogWakes.get() > catalogWakesBeforeImport);
              return null;
            });

    assertFalse(
        notifiedInsideTransaction.get(),
        "afterCommit callbacks must not be observable inside the import transaction");
    assertEquals(
        catalogWakesBeforeImport + 1,
        catalogWakes.get(),
        "a committed import must request exactly one catalog refresh");
    assertEquals(
        dispatcherWakesBeforeImport + 1,
        dispatcherWakes.get(),
        "a rewritten credential must wake the refresh dispatcher once");

    PluginCredentialRow after = credentialRepository.find(STATIC_PLUGIN_ID).orElseThrow();
    assertNotEquals(seeded.authGeneration(), after.authGeneration());
    assertNotEquals(seeded.contextFingerprint(), after.contextFingerprint());
    assertFalse(
        Arrays.equals(seeded.encryptedPayload(), after.encryptedPayload()),
        "the credential must be rewritten under the new authentication context");
    PluginCredentialReference newReference =
        new PluginCredentialReference(
            STATIC_PLUGIN_ID, after.authGeneration(), after.contextFingerprint());
    assertEquals(
        "{\"apiKey\":\"" + SYNTHETIC_SECRET + "\"}",
        credentialStore.resolve(newReference).payloadJson(),
        "the import must keep the exact same secret payload");
    assertThrows(
        PluginCredentialUnavailableException.class, () -> credentialStore.resolve(oldReference));

    PluginConfigurationRow history =
        configurationRepository.readConfiguration(STATIC_PLUGIN_ID, 3L).orElseThrow();
    assertEquals(VALUES_V2, history.valuesJson());
    assertEquals(List.of("apiKey"), history.configuredSecretFields());
    assertEquals(newReference.authGeneration(), history.authGeneration());
    assertEquals(newReference.contextFingerprint(), history.contextFingerprint());
  }

  /** 以真实 Bootstrap 在 epoch 0 上初始化 epoch 1 的权威目录：安装定义与默认普通值直接来自合成插件。 */
  private void seedCatalog() {
    PluginCatalogBootstrap bootstrap =
        new PluginCatalogBootstrap(registry, configurationRepository, transactionManager);
    try (PluginCatalogSnapshot ignored =
        bootstrap.bootstrap(
            new PluginContextFactory(credentialStore, () -> null, () -> null, () -> null)::create,
            List.of(),
            List.of())) {
      // 初始化即已落库 epoch 1；快照本身不参与本测试的读写断言。
    }
  }

  /** 外层真实事务边界：与应用中的 {@code @Transactional} 提交/回滚语义一致。 */
  private void applyInTransaction(ConfigSyncPlan plan) {
    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              applier.apply(plan);
              return null;
            });
  }

  private static ConfigSyncPlan plan(
      List<ConfigSyncParser.ProviderSpec> providers,
      List<ConfigSyncPlan.PluginImport> plugins,
      long pluginCatalogEpoch) {
    return new ConfigSyncPlan(
        providers,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        plugins,
        pluginCatalogEpoch,
        null,
        List.of(),
        List.of());
  }

  private static ConfigSyncParser.ProviderSpec providerSpec(String name) {
    AgentProviderEditablePropertiesDTO properties = new AgentProviderEditablePropertiesDTO();
    properties.setProviderType("openai");
    properties.setBaseUrl("https://api.example.com");
    properties.setCredential("synthetic");
    return new ConfigSyncParser.ProviderSpec(name, properties);
  }

  /** USER 型 Agent 必须绑定真实 model：合成 provider + model 走真实外键，绝不旁路约束。 */
  private static void seedAgentModel() {
    jdbcClient
        .sql(
            "insert into agent_provider (name, provider_type, config, connection_generation_id)"
                + " values (:name, 'openai', '{}'::jsonb, cast(:generation as uuid))")
        .param("name", MODEL_PROVIDER)
        .param("generation", UUID.randomUUID().toString())
        .update();
    jdbcClient
        .sql(
            "insert into agent_model (provider_name, name, model_id, config)"
                + " values (:provider, :name, :modelId, '{}'::jsonb)")
        .param("provider", MODEL_PROVIDER)
        .param("name", MODEL_NAME)
        .param("modelId", "synthetic-model")
        .update();
  }

  private static AgentDefinition agentDefinition(String name) {
    AgentDefinition agent = new AgentDefinition();
    agent.setName(name);
    agent.setType(AgentDefinitionType.USER);
    agent.setSystemPrompt("prompt " + name);
    agent.setModelProviderName(MODEL_PROVIDER);
    agent.setModelName(MODEL_NAME);
    agent.setConfigJson(
        "{\"tools\":[],\"skills\":[],\"subagents\":[],\"inheritParentEnvironment\":true}");
    agent.setVersion(0L);
    return agent;
  }

  private static long count(String sql) {
    return jdbcClient.sql(sql).query(Long.class).single();
  }

  private static long pluginHistoryCount(String pluginId) {
    return count(
        "select count(*) from plugin_configuration_revision where plugin_id = '" + pluginId + "'");
  }

  /** 合成静态密钥插件：普通值含一个认证上下文字段，秘密字段由部署主密钥加密。 */
  private static final class StaticKeyPlugin implements StudioPlugin {

    private static final PluginConfigSchema SCHEMA =
        new PluginConfigSchema(
            new InputSchema(
                "config",
                Map.of(
                    "endpoint", new StringSchema("endpoint"), "label", new StringSchema("label")),
                Set.of(),
                false),
            "{\"endpoint\":\"https://api.example.com\",\"label\":\"default\"}",
            Map.of("apiKey", "synthetic API key"));

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(STATIC_PLUGIN_ID, "ConfigSync static plugin", "1.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return SCHEMA;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.StaticKey("apiKey", Set.of("endpoint"));
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return TestPluginFixtures.emptyInstance();
    }
  }
}

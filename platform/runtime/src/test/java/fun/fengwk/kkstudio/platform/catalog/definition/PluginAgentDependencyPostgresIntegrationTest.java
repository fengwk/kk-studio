package fun.fengwk.kkstudio.platform.catalog.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.plugin.service.PluginConfigurationService;
import fun.fengwk.kkstudio.platform.plugin.service.PluginContextFactory;
import fun.fengwk.kkstudio.platform.plugin.testing.TestPluginFixtures;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 插件普通配置声明与 Agent 删除的一致性的真实 PostgreSQL 契约。
 *
 * <p>只在这里对真实库、真实事务管理器与真实提交服务成立：
 *
 * <ol>
 *   <li>常规保存先在写事务内按声明取 Agent 行锁并核验存在，再做 revision CAS：缺 Agent 时修订、凭据与通知一概不变。
 *   <li>Agent 删除在自己行锁之后、真实删除之前读插件当前配置：被插件的普通配置引用即拒绝。
 *   <li>并发 save-vs-delete 的两个锁顺序都不能提交悬空引用：谁先取得 Agent 行锁谁成功，另一方确定性失败。
 * </ol>
 *
 * <p>合成 StudioPlugin 只用公共 API 声明普通值字段与声明的 Agent 名称，不引用任何具体插件实现，也不访问网络或真实凭据。
 */
class PluginAgentDependencyPostgresIntegrationTest {

  private static final String DECLARING_PLUGIN_ID = "configsync.dep.plugin";
  private static final String DECLARED_AGENT = "declared-agent";
  private static final String MODEL_PROVIDER = "plugin-agent-model-provider";
  private static final String MODEL_NAME = "plugin-agent-model";
  private static final String VALUES_NO_DECLARATION = "{}";
  private static final String VALUES_DECLARING = "{\"agentName\":\"" + DECLARED_AGENT + "\"}";
  private static final String VALUES_DECLARING_GHOST = "{\"agentName\":\"ghost-agent\"}";

  private static DataSource dataSource;
  private static JdbcClient jdbcClient;

  private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);
  private final AtomicInteger dispatcherWakes = new AtomicInteger();
  private final AtomicInteger catalogWakes = new AtomicInteger();

  private StudioPluginRegistry registry;
  private PlatformTransactionManager transactionManager;
  private PluginConfigurationRepository configurationRepository;
  private PluginCredentialRepository credentialRepository;
  private PluginConfigurationService configurationService;
  private AgentDefinitionRepository definitionRepository;
  private PluginAgentDependencies pluginAgentDependencies;
  private AgentDefinitionService definitionService;

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
    registry = new StudioPluginRegistry(List.of(new DeclaringPlugin(DECLARING_PLUGIN_ID)));
    transactionManager = new DataSourceTransactionManager(dataSource);
    configurationRepository = new PluginConfigurationRepository(jdbcClient);
    credentialRepository = new PluginCredentialRepository(jdbcClient);
    definitionRepository = new AgentDefinitionRepository(jdbcClient);
    pluginAgentDependencies =
        new PluginAgentDependencies(registry, configurationRepository, definitionRepository);

    configurationService = configurationServiceWith(pluginAgentDependencies);

    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            definitionRepository,
            mock(AgentModelRepository.class),
            mock(SkillPackageRepository.class),
            mock(McpServerRepository.class),
            TestRuntimeToolCatalogSource.of(HarnessCatalog.from(List.of())));
    definitionService =
        new AgentDefinitionService(
            definitionRepository,
            resolver,
            mock(AgentModelDefaultVariantResolver.class),
            new AgentDefinitionConfigValidator(
                TestRuntimeToolCatalogSource.of(HarnessCatalog.from(List.of()))),
            new AgentDefinitionConfigCodec(JsonMapper.builder().build()),
            new AgentEditableSupport(JsonMapper.builder().build()),
            pluginAgentDependencies);

    configurationRepository.insertIfMissing(DECLARING_PLUGIN_ID, VALUES_NO_DECLARATION);
  }

  /** 缺 Agent 的保存：固定去敏 400，修订、凭据与 afterCommit 通知一概不变。 */
  @Test
  void configureRejectsAMissingDeclaredAgentWithoutAnyWrite() {
    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () ->
                configurationService.configure(
                    DECLARING_PLUGIN_ID, 1L, VALUES_DECLARING_GHOST, Map.of()));

    assertEquals("plugins reference a missing agent", error.getMessage());
    assertTrue(error.getCause() == null, "the dependency failure must not carry a cause");
    assertEquals(1L, currentRevision(), "a rejected save must not advance the revision");
    assertEquals(0L, credentialRowCount(), "a rejected save must not create a credential row");
    assertEquals(0, catalogWakes.get());
    assertEquals(0, dispatcherWakes.get());
  }

  /** 正向对照：声明的 Agent 存在时保存照常提交，依赖核验不误伤合法配置。 */
  @Test
  void configureAcceptsAnExistingDeclaredAgent() {
    seedAgentModel();
    assertTrue(definitionRepository.create(agentDefinition(DECLARED_AGENT)));
    assertEquals(
        2L, configurationService.configure(DECLARING_PLUGIN_ID, 1L, VALUES_DECLARING, Map.of()));

    assertEquals(VALUES_DECLARING, currentValues());
    assertEquals(1, catalogWakes.get());
    assertEquals(0, dispatcherWakes.get());
  }

  /** 当前配置引用了该 Agent 时拒绝删除：Agent 行与插件配置都不变。 */
  @Test
  void deleteRejectsAnAgentReferencedByTheDesiredPluginConfiguration() {
    seedAgentModel();
    assertTrue(definitionRepository.create(agentDefinition(DECLARED_AGENT)));
    configurationService.configure(DECLARING_PLUGIN_ID, 1L, VALUES_DECLARING, Map.of());

    AiInUseException error =
        assertThrows(AiInUseException.class, () -> deleteInTransaction(DECLARED_AGENT, 0L));

    assertTrue(error.getMessage().contains("referenced by plugin configuration"));
    assertNotNull(definitionRepository.getByName(DECLARED_AGENT), "the agent must survive");
    assertEquals(2L, currentRevision());
  }

  /** 移除引用后允许删除：当前配置不再声明即不再保护该 Agent。 */
  @Test
  void deleteSucceedsAfterTheDeclaringConfigurationIsRemoved() {
    seedAgentModel();
    assertTrue(definitionRepository.create(agentDefinition(DECLARED_AGENT)));
    configurationService.configure(DECLARING_PLUGIN_ID, 1L, VALUES_DECLARING, Map.of());
    configurationService.configure(DECLARING_PLUGIN_ID, 2L, VALUES_NO_DECLARATION, Map.of());

    deleteInTransaction(DECLARED_AGENT, 0L);

    assertEquals(null, definitionRepository.getByName(DECLARED_AGENT));
    assertEquals(VALUES_NO_DECLARATION, currentValues());
  }

  /** 并发方向一：保存先取得 Agent 行锁并提交带引用的配置，随后删除必须拒绝，绝不产生悬空引用。 */
  @Test
  void saveWinningTheAgentRowLockMakesTheConcurrentDeleteReject() throws Exception {
    seedAgentModel();
    assertTrue(definitionRepository.create(agentDefinition(DECLARED_AGENT)));
    CountDownLatch saveLockHeld = new CountDownLatch(1);
    CountDownLatch releaseSave = new CountDownLatch(1);
    PluginAgentDependencies gatedDependencies =
        new PluginAgentDependencies(
            registry,
            configurationRepository,
            new GatedAgentDefinitionRepository(jdbcClient, saveLockHeld, releaseSave));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      // 保存侧被闸门停在「已持有 Agent 行锁、尚未提交」的位置，删除才有确定的相反锁顺序。
      Future<Long> save =
          executor.submit(
              () ->
                  configurationServiceWith(gatedDependencies)
                      .configure(DECLARING_PLUGIN_ID, 1L, VALUES_DECLARING, Map.of()));
      awaitLatch(saveLockHeld, "the save must reach the Agent row lock before the delete starts");

      Future<AiInUseException> delete =
          executor.submit(
              () -> {
                try {
                  deleteInTransaction(DECLARED_AGENT, 0L);
                  return null;
                } catch (AiInUseException error) {
                  return error;
                }
              });
      // 真实库证明删除确实在等待同一 Agent 行锁，而不是等保存提交后才开始。
      awaitUngrantedRowLock();

      releaseSave.countDown();
      assertEquals(2L, save.get(30, TimeUnit.SECONDS));
      AiInUseException rejection = delete.get(30, TimeUnit.SECONDS);
      assertNotNull(rejection, "the delete must reject an agent the committed save references");
      assertTrue(rejection.getMessage().contains("referenced by plugin configuration"));
    } finally {
      releaseSave.countDown();
      shutdownWorkers(executor);
    }

    assertNotNull(definitionRepository.getByName(DECLARED_AGENT), "the agent must survive");
    assertEquals(
        VALUES_DECLARING, currentValues(), "the committed reference must be the desired one");
    assertEquals(2L, currentRevision(), "the committed save must own exactly one revision");
  }

  /** 并发方向二：删除先取得 Agent 行锁并在保存真的等待该锁后才提交，保存必须固定失败且零写。 */
  @Test
  void deleteWinningTheAgentRowLockMakesTheConcurrentSaveFail() throws Exception {
    seedAgentModel();
    assertTrue(definitionRepository.create(agentDefinition(DECLARED_AGENT)));
    CountDownLatch deleteLockHeld = new CountDownLatch(1);
    CountDownLatch releaseDelete = new CountDownLatch(1);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Void> delete =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(
                          status -> {
                            // 与生产删除同一锁序：先取自身 Agent 行锁，闸门放行前绝不提交。
                            definitionRepository.getByNameForUpdate(DECLARED_AGENT);
                            deleteLockHeld.countDown();
                            awaitLatchQuietly(releaseDelete);
                            definitionService.deleteAgent(DECLARED_AGENT, "0");
                            return null;
                          }));
      awaitLatch(deleteLockHeld, "the delete must hold the Agent row lock before the save starts");

      Future<AiValidationException> save =
          executor.submit(
              () -> {
                try {
                  configurationService.configure(
                      DECLARING_PLUGIN_ID, 1L, VALUES_DECLARING, Map.of());
                  return null;
                } catch (AiValidationException error) {
                  return error;
                }
              });
      // 真实库证明保存正阻塞在同一 Agent 行锁上：此时删除尚未提交，方向是确定的「删除先持锁」。
      awaitUngrantedRowLock();

      releaseDelete.countDown();
      delete.get(30, TimeUnit.SECONDS);
      AiValidationException rejection = save.get(30, TimeUnit.SECONDS);
      assertNotNull(rejection, "the save must fail once the agent was deleted");
      assertEquals("plugins reference a missing agent", rejection.getMessage());
      assertTrue(rejection.getCause() == null, "the dependency failure must not carry a cause");
    } finally {
      releaseDelete.countDown();
      shutdownWorkers(executor);
    }

    assertEquals(null, definitionRepository.getByName(DECLARED_AGENT));
    assertEquals(1L, currentRevision(), "a rejected save must leave the revision untouched");
    assertEquals(
        VALUES_NO_DECLARATION, currentValues(), "the values must stay the installed default");
    assertEquals(0L, credentialRowCount());
    assertEquals(0, catalogWakes.get());
    assertEquals(0, dispatcherWakes.get());
  }

  /** 等待闸门或锁步信号；超时即失败，不使用固定睡眠做同步。 */
  private static void awaitLatch(CountDownLatch latch, String message) {
    try {
      assertTrue(latch.await(30, TimeUnit.SECONDS), message);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while " + message);
    }
  }

  /** worker 内部的闸门等待：被中断时以断言失败结束，让所在事务正常回滚。 */
  private static void awaitLatchQuietly(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new AssertionError("the gated worker was not released");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for the gate");
    }
  }

  /**
   * 先释放闸门再关停 worker，并断言它们真的退出：断言失败时也不留下持有事务的线程。
   *
   * <p>调用方必须已经 {@code countDown} 自己的闸门，否则这里退化为中断挂起线程并在超时后失败。
   */
  private static void shutdownWorkers(ExecutorService executor) {
    executor.shutdown();
    try {
      assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "concurrent workers must exit");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for the concurrent workers to exit");
    } finally {
      executor.shutdownNow();
    }
  }

  /** 与生产装配一致、只替换依赖服务的写入口：其余依赖原样复用。 */
  private PluginConfigurationService configurationServiceWith(
      PluginAgentDependencies dependencies) {
    return new PluginConfigurationService(
        registry,
        configurationRepository,
        credentialRepository,
        new PluginContextFactory(
            new DatabasePluginCredentialStore(credentialRepository, clock),
            () -> null,
            () -> null,
            () -> null),
        dependencies,
        transactionManager,
        catalogWakes::incrementAndGet,
        dispatcherWakes::incrementAndGet);
  }

  /** 与生产一致的事务边界：删除入口自身在真实事务内运行。 */
  private void deleteInTransaction(String name, long expectedVersion) {
    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              definitionService.deleteAgent(name, String.valueOf(expectedVersion));
              return null;
            });
  }

  private void seedAgentModel() {
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
    AgentDefinition agent =
        AgentDefinition.builder()
            .name(name)
            .type(AgentDefinitionType.USER)
            .systemPrompt("prompt " + name)
            .modelProviderName(MODEL_PROVIDER)
            .modelName(MODEL_NAME)
            .configJson(
                "{\"tools\":[],\"skills\":[],\"subagents\":[],\"inheritParentEnvironment\":true}")
            .version(0L)
            .build();
    return agent;
  }

  /** 轮询真实库等待行锁竞争者出现：以可观测状态同步，不依赖固定等待时长。 */
  private void awaitUngrantedRowLock() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      Long waiting =
          jdbcClient
              .sql("select count(*) from pg_locks where locktype = 'transactionid' and not granted")
              .query(Long.class)
              .single();
      if (waiting != null && waiting > 0) {
        return;
      }
      try {
        Thread.sleep(20L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for the row lock contention");
      }
    }
    throw new AssertionError("no transaction was waiting for the Agent row lock");
  }

  private long currentRevision() {
    return configurationRepository.find(DECLARING_PLUGIN_ID).orElseThrow().revision();
  }

  private String currentValues() {
    return configurationRepository.find(DECLARING_PLUGIN_ID).orElseThrow().valuesJson();
  }

  private long credentialRowCount() {
    return jdbcClient
        .sql("select count(*) from plugin_credential where plugin_id = :id")
        .param("id", DECLARING_PLUGIN_ID)
        .query(Long.class)
        .single();
  }

  /** 只在保存侧生效的闸门：代理真实仓库，在取得 Agent 行锁后通知测试并等待放行，用于确定性地制造「保存先持锁」的顺序。 */
  private static final class GatedAgentDefinitionRepository extends AgentDefinitionRepository {

    private final CountDownLatch lockAcquired;
    private final CountDownLatch release;

    GatedAgentDefinitionRepository(
        JdbcClient jdbcClient, CountDownLatch lockAcquired, CountDownLatch release) {
      super(jdbcClient);
      this.lockAcquired = lockAcquired;
      this.release = release;
    }

    @Override
    public AgentDefinition getByNameForUpdate(String name) {
      AgentDefinition locked = super.getByNameForUpdate(name);
      lockAcquired.countDown();
      awaitLatchQuietly(release);
      return locked;
    }
  }

  /** 合成插件：普通配置声明一个 Agent 名称字段，{@code requiredAgentNames} 只解析该字段。 */
  private static final class DeclaringPlugin implements StudioPlugin {

    private static final PluginConfigSchema SCHEMA =
        new PluginConfigSchema(
            new InputSchema(
                "config",
                Map.of("agentName", new StringSchema("declared agent name")),
                Set.of(),
                false),
            "{}",
            Map.of());

    private final String pluginId;

    DeclaringPlugin(String pluginId) {
      this.pluginId = pluginId;
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(pluginId, "Synthetic " + pluginId, "1.0.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return SCHEMA;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.None();
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return TestPluginFixtures.emptyInstance();
    }

    @Override
    public Set<String> requiredAgentNames(PluginConfig config) {
      JsonNode node = JsonValues.readTree(config.valuesJson());
      JsonNode agent = node.get("agentName");
      if (agent == null || !agent.isString() || agent.stringValue().isBlank()) {
        return Set.of();
      }
      return Set.of(agent.stringValue());
    }
  }
}

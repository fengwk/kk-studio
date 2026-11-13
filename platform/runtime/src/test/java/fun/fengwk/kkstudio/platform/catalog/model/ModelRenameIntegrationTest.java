package fun.fengwk.kkstudio.platform.catalog.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 模型改名的引用侧写入：先按 name 锁定全部引用 Agent，再以一条 UPDATE 同步引用与版本。
 *
 * <p>测试意图：多个 Builtin Agent 引用同一模型时只发出一条引用更新；其他 Driver 保持不动。改名仍等待引用 Agent 的行锁，锁序不变。
 */
class ModelRenameIntegrationTest {

  private JdbcClient fixture;
  private JdbcClient observed;
  private ModelRepository repository;
  private TransactionTemplate transaction;

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
    repository = new ModelRepository(observed);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @Test
  void renameRewritesEveryBuiltinReferenceWithOneUpdate() {
    insertProvider("provider");
    insertModel("model", "provider");
    insertAgent("zeta", "model", "builtin");
    insertAgent("alpha", "model", "builtin");
    insertAgent("manual", "model", "other");

    transaction.executeWithoutResult(
        status -> {
          assertNotNull(repository.getByNameForUpdate("model"));
          repository.rename("model", renamedModel("model-renamed", "provider"), 0L);
        });

    verify(observed, times(1)).sql(contains("update agent set"));
    assertAgentReference("alpha", "model-renamed", 1L);
    assertAgentReference("zeta", "model-renamed", 1L);
    assertAgentReference("manual", "model", 0L);
    assertEquals(
        1L,
        fixture
            .sql("select version from model where name = 'model-renamed'")
            .query(Long.class)
            .single());
  }

  @Test
  void renameWaitsForReferencingAgentRowLockBeforeUpdating() throws Exception {
    insertProvider("provider");
    insertModel("model", "provider");
    insertAgent("alpha", "model", "builtin");
    insertAgent("zeta", "model", "builtin");

    CountDownLatch agentLocked = new CountDownLatch(1);
    CountDownLatch releaseAgent = new CountDownLatch(1);
    CountDownLatch renameStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> holder =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        fixture
                            .sql("select name from agent where name = :name for update")
                            .param("name", "zeta")
                            .query(String.class)
                            .single();
                        agentLocked.countDown();
                        await(releaseAgent);
                      }));
      assertTrue(agentLocked.await(5, TimeUnit.SECONDS));

      Future<?> rename =
          executor.submit(
              () -> {
                renameStarted.countDown();
                transaction.executeWithoutResult(
                    status -> {
                      repository.getByNameForUpdate("model");
                      repository.rename("model", renamedModel("model-renamed", "provider"), 0L);
                    });
                return null;
              });
      assertTrue(renameStarted.await(5, TimeUnit.SECONDS));
      Thread.sleep(300);
      assertFalse(rename.isDone(), "rename must wait for the referencing agent row lock");

      releaseAgent.countDown();
      holder.get(5, TimeUnit.SECONDS);
      rename.get(5, TimeUnit.SECONDS);
    } finally {
      releaseAgent.countDown();
      executor.shutdownNow();
    }

    assertAgentReference("alpha", "model-renamed", 1L);
    assertAgentReference("zeta", "model-renamed", 1L);
  }

  private void assertAgentReference(String agentName, String modelName, long version) {
    assertEquals(
        modelName,
        fixture
            .sql(
                "select execution -> 'settings' ->> 'modelName' from agent" + " where name = :name")
            .param("name", agentName)
            .query(String.class)
            .single());
    assertEquals(
        version,
        fixture
            .sql("select version from agent where name = :name")
            .param("name", agentName)
            .query(Long.class)
            .single());
  }

  private Model renamedModel(String name, String providerName) {
    return Model.builder()
        .name(name)
        .kind(ModelKind.MODEL)
        .providerName(providerName)
        .modelId("wire-model")
        .description("renamed")
        .configJson("{}")
        .build();
  }

  private void insertProvider(String name) {
    fixture
        .sql(
            """
            insert into provider (name, provider_type, config)
            values (:name, 'openai', '{}'::jsonb)
            """)
        .param("name", name)
        .update();
  }

  private void insertModel(String name, String providerName) {
    fixture
        .sql(
            """
            insert into model (name, kind, provider_name, model_id, config)
            values (:name, 'MODEL', :providerName, 'wire-model', '{}'::jsonb)
            """)
        .param("name", name)
        .param("providerName", providerName)
        .update();
  }

  private void insertAgent(String name, String modelName, String driverName) {
    fixture
        .sql(
            """
            insert into agent (name, type, execution, config)
            values (:name, 'BUILTIN',
                    jsonb_build_object('driver', :driver,
                        'settings', jsonb_build_object(
                            'modelName', :modelName, 'variant', 'default')),
                    '{}'::jsonb)
            """)
        .param("name", name)
        .param("driver", driverName)
        .param("modelName", modelName)
        .update();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for concurrent transaction");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }
}

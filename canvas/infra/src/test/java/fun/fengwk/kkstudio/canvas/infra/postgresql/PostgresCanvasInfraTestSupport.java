package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.infra.CanvasInfraTestApplication;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionDispatcher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

/**
 * Canvas Infra 真实 PostgreSQL 测试基座。
 *
 * <p>沿用仓库既有模式：进程级 {@code postgres:17-alpine} Testcontainer、权威 schema 模块中的 Flyway baseline，以及每个测试前
 * drop/recreate public schema。baseline 之后由 {@link CanvasTargetSchemaFixture} 重建 canvas
 * 目标表，使适配器始终面对目标模型。 Docker 不可用时测试直接失败，不以 mock 或跳过掩盖适配器问题。
 */
@SpringBootTest(classes = CanvasInfraTestApplication.class)
public abstract class PostgresCanvasInfraTestSupport {

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio_canvas_infra")
          .withUsername("kk_studio")
          .withPassword("kk_studio");

  static {
    POSTGRES.start();
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
      migrateDatabase(connection);
    } catch (SQLException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected TransactionTemplate transactions;
  @Autowired protected CanvasStore canvasStore;
  @Autowired protected CanvasResourceRepository resourceRepository;
  @MockitoBean private CanvasFunctionDispatcher dispatcher;

  @DynamicPropertySource
  static void configurePostgres(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> "false");
  }

  @BeforeEach
  final void resetSchema() throws SQLException {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
      migrateDatabase(connection);
    }
  }

  protected UUID addDocument() {
    UUID canvasId = UUID.randomUUID();
    canvasStore.addDocument(canvasId, "canvas-" + canvasId);
    return canvasId;
  }

  /** 只插入节点行，用于不需要资源行的场景（Run claim 等）。 */
  protected NodeRecord addNode(UUID canvasId, boolean function) {
    NodeRecord node =
        newNode(canvasId)
            .record(
                function
                    ? new CanvasFunction("video.generate", CanvasJson.parseObject("{}"))
                    : null);
    canvasStore.addNode(node);
    return node;
  }

  /** 普通资源节点：一个文本资源占据 index 0，节点行与资源行同时落库。 */
  protected NodeFixture addTextNode(UUID canvasId, String text) {
    NodeFixture fixture = newNode(canvasId);
    canvasStore.addNode(fixture.record(null));
    fixture.resourceId = UUID.randomUUID();
    resourceRepository.add(fixture.textResource(0, text));
    return fixture;
  }

  /** Function 节点：携带 {@code {name,args}} 配置；输出资源在成功物化前不存在。 */
  protected NodeFixture addFunctionNode(UUID canvasId, String functionName) {
    NodeFixture fixture = newNode(canvasId);
    canvasStore.addNode(
        fixture.record(new CanvasFunction(functionName, CanvasJson.parseObject("{}"))));
    return fixture;
  }

  private static NodeFixture newNode(UUID canvasId) {
    UUID nodeId = UUID.randomUUID();
    return new NodeFixture(
        nodeId, canvasId, "node-" + nodeId, new CanvasTransform(10, 20, 300, 200));
  }

  /**
   * 直接插入 Run 行时使用的最小合法 state_json。
   *
   * <p>state_json 的 stage 是持久化事实的一部分，测试夹具不能写入无法被 codec 读回的行。
   */
  protected static String minimalRunState(String stage) {
    return "{\"stage\":\"" + stage + "\"}";
  }

  protected static Connection newConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void resetDatabase(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists public cascade");
      statement.execute("create schema public");
    }
  }

  private static void migrateDatabase(Connection connection) throws SQLException {
    Flyway.configure()
        .dataSource(new SingleConnectionDataSource(connection, true))
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .load()
        .migrate();
    CanvasTargetSchemaFixture.apply(connection);
  }

  /** 节点夹具：保持节点行、资源行与领域投影一致，便于测试直接表达目标模型。 */
  protected static final class NodeFixture {

    protected final UUID nodeId;
    protected final UUID canvasId;
    protected final String name;
    protected final CanvasTransform transform;
    protected UUID groupId;

    /** index 0 的文本资源 id；{@link #addTextNode} 之后可用。 */
    protected UUID resourceId;

    private NodeFixture(UUID nodeId, UUID canvasId, String name, CanvasTransform transform) {
      this.nodeId = nodeId;
      this.canvasId = canvasId;
      this.name = name;
      this.transform = transform;
    }

    protected NodeRecord record(CanvasFunction function) {
      return new NodeRecord(nodeId, canvasId, name, transform, groupId, function);
    }

    /** 同一节点的另一种分组归属形态，用于验证节点行全量写入。 */
    protected NodeRecord record(UUID groupId, CanvasFunction function) {
      return new NodeRecord(nodeId, canvasId, name, transform, groupId, function);
    }

    /** 同一节点的改名形态，用于验证兼容折叠唯一键冲突。 */
    protected NodeRecord renamed(String newName, CanvasFunction function) {
      return new NodeRecord(nodeId, canvasId, newName, transform, groupId, function);
    }

    /** 资源 id 由夹具分配：index 0 复用 {@link #resourceId}，其余位置为新行。 */
    protected CanvasResource textResource(int index, String text) {
      UUID id = index == 0 && resourceId != null ? resourceId : UUID.randomUUID();
      return new CanvasResource(id, canvasId, nodeId, index, null, "text", text, Instant.now());
    }
  }
}

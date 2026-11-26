package fun.fengwk.kkstudio.canvas.infra.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasDocuments;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResourceInput;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.CanvasUploadOwnership;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.infra.dto.CanvasDtoMapper;
import fun.fengwk.kkstudio.canvas.infra.function.ClaimedRun;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasCommandService;
import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.work.BoundedWorkDispatcher;

import java.util.List;
import java.util.UUID;

/**
 * Platform 装配的 Canvas → 全局 Storage Blob 引用释放契约。
 *
 * <p>生产上下文里的 {@code CanvasBlobReleaser} 适配真实 {@code StorageBlobManager}：Resource 行删除与 Blob
 * 引用减一必须落在同一个 Canvas 事务里，引用归零时 Blob 只切换为 DELETING（对象字节留给 Storage 维护）。Release 无法完成时必须抛出并使整个 Canvas
 * 事务回滚，而不是留下已删除资源却仍被引用的 Blob。
 *
 * <p>后台维护被替换为 mock，使「释放只切换 DELETING」与「释放失败回滚」的断言不受并发 DELETING 行清扫影响；对象与行的真实回收不在本契约内。
 */
@Import({
  CanvasPlatformTestConfiguration.class,
  CanvasBlobReleaseIntegrationTest.CallerConfiguration.class
})
class CanvasBlobReleaseIntegrationTest extends PostgresSpringTestSupport {

  private static final CanvasTransform TRANSFORM = new CanvasTransform(10, 20, 100, 80);
  private static final UUID USER_ID = UUID.randomUUID();

  @Autowired private CanvasCommandService commandService;
  @Autowired private CanvasDocuments documents;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationContext context;
  @Autowired private TransactionalCanvasCaller transactionalCaller;
  @MockitoBean private StorageMaintenance storageBlobSweeper;
  @MockitoBean private CanvasUploadOwnership uploadOwnership;

  @BeforeEach
  void configureReadyUploadFixture() {
    when(uploadOwnership.lockReady(any(UUID.class), eq(USER_ID)))
        .thenAnswer(
            invocation ->
                new CanvasUploadOwnership.ReadyUpload(invocation.getArgument(0), "stored.png"));
  }

  @MockitoBean(name = "canvasFunctionDispatcher")
  private BoundedWorkDispatcher<ClaimedRun> canvasFunctionDispatcher;

  /** 外部事务决定创建提交或回滚，服务调用不能提前提交。 */
  @Test
  void createCanvasJoinsExternalTransaction() {
    CanvasDocument committed = transactionalCaller.createAndCommit("external-commit");
    assertEquals(0L, committed.revision());
    assertEquals(
        1L,
        count("canvas_document", "id = ? and revision = ?", committed.id(), committed.revision()));

    UUID rolledBackId =
        assertThrows(
                RollbackAfterCreate.class,
                () -> transactionalCaller.createAndRollback("external-rollback"))
            .canvasId;
    assertEquals(0L, count("canvas_document", "id = ?", rolledBackId));
  }

  /** 调用方回滚须恢复整个图、Work 与 Blob 引用。 */
  @Test
  void deleteCanvasJoinsExternalTransaction() {
    Graph graph = addGraph(CanvasFunctionRunStatus.SUCCEEDED);
    assertThrows(
        RollbackAfterDelete.class,
        () -> transactionalCaller.deleteAndRollback(graph.canvasId(), graph.revision()));
    assertGraphIntact(graph, CanvasFunctionRunStatus.SUCCEEDED);
  }

  /** 清理带引用、pin 和两条共享同一 Blob 的资源边的画布。 */
  @Test
  void deleteCanvasRemovesGraphAndReleasesEachBlobEdge() {
    Graph graph = addGraph(CanvasFunctionRunStatus.SUCCEEDED);
    documents.delete(graph.canvasId(), graph.revision());
    assertEquals(List.of(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), graphRows(graph));
    assertEquals(0L, refCount(graph.blobId()));
    assertEquals("DELETING", blobState(graph.blobId()));
  }

  /** 三种未结算状态均保护整个图；UNKNOWN 不得重新请求 Work。 */
  @ParameterizedTest
  @EnumSource(
      value = CanvasFunctionRunStatus.class,
      names = {"READY", "RUNNING", "UNKNOWN"})
  void deleteCanvasRejectsUnsettledRun(CanvasFunctionRunStatus status) {
    Graph graph = addGraph(status);
    DomainException error =
        assertThrows(
            DomainException.class, () -> documents.delete(graph.canvasId(), graph.revision()));
    assertEquals(DomainErrorCode.CONFLICT, error.code());
    assertEquals("EXECUTION_NOT_SETTLED", error.reason());
    assertGraphIntact(graph, status);
  }

  /** 空画布深删除后不留文档行。 */
  @Test
  void deleteEmptyCanvas() {
    CanvasDocument canvas = documents.create(UUID.randomUUID(), "empty");
    documents.delete(canvas.id(), canvas.revision());
    assertEquals(0L, count("canvas_document", "id = ?", canvas.id()));
  }

  /** 过期 revision 拒绝删除，文档仍然存在。 */
  @Test
  void deleteCanvasRejectsRevisionMismatch() {
    Graph graph = addGraph(CanvasFunctionRunStatus.SUCCEEDED);
    DomainException error =
        assertThrows(
            DomainException.class, () -> documents.delete(graph.canvasId(), graph.revision() - 1));
    assertEquals(DomainErrorCode.VERSION_CONFLICT, error.code());
    assertEquals(graph.revision() - 1, error.expectedVersion());
    assertEquals(graph.revision(), error.actualVersion());
    assertGraphIntact(graph, CanvasFunctionRunStatus.SUCCEEDED);
  }

  /** 不存在的画布以类型化领域错误拒绝。 */
  @Test
  void deleteMissingCanvas() {
    assertEquals(
        DomainErrorCode.NOT_FOUND,
        assertThrows(DomainException.class, () -> documents.delete(UUID.randomUUID(), 0)).code());
  }

  /** 平台适配根显式注册唯一 mapper，Web 无需自行扫描或装配它。 */
  @Test
  void platformRegistersSingleCanvasDtoMapper() {
    assertEquals(1, context.getBeansOfType(CanvasDtoMapper.class).size());
  }

  /** 删除节点：节点 Resource 行与它持有的 Blob 引用同事务释放，引用归零后 Blob 转为 DELETING。 */
  @Test
  void deleteNodeReleasesBoundBlobReference() {
    UUID blobId = insertBlob("ACTIVE", 1L);
    CanvasDocument canvas = documents.create(UUID.randomUUID(), "blob-release");
    UUID nodeId = UUID.randomUUID();
    apply(
        canvas,
        new CanvasCommand.CreateNode(
            nodeId, "node", TRANSFORM, List.of(new CanvasResourceInput.Blob("a.png", blobId))));
    UUID resourceId = ownedResourceId(canvas.id(), nodeId);

    apply(canvas, new CanvasCommand.DeleteNode(nodeId, List.of(resourceId), null));

    assertEquals(0L, count("canvas_resource", "canvas_id = ? and id = ?", canvas.id(), resourceId));
    assertEquals(0L, refCount(blobId), "Resource 行删除必须同事务释放 Blob 引用");
    assertEquals("DELETING", blobState(blobId));
  }

  /** Blob 已无法释放（非 ACTIVE）时节点删除整批回滚，节点与资源行仍然存在。 */
  @Test
  void failedBlobReleaseRollsBackNodeDeletion() {
    UUID blobId = insertBlob("DELETING", 0L);
    CanvasDocument canvas = documents.create(UUID.randomUUID(), "blob-release-failure");
    UUID nodeId = UUID.randomUUID();
    apply(
        canvas,
        new CanvasCommand.CreateNode(
            nodeId, "node", TRANSFORM, List.of(new CanvasResourceInput.Blob("a.png", blobId))));
    UUID resourceId = ownedResourceId(canvas.id(), nodeId);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> apply(canvas, new CanvasCommand.DeleteNode(nodeId, List.of(resourceId), null)));

    assertEquals("release canvas resource blob failed: " + blobId, error.getMessage());
    assertEquals(1L, count("canvas_node", "canvas_id = ? and id = ?", canvas.id(), nodeId));
    assertEquals(
        1L,
        count("canvas_resource", "canvas_id = ? and id = ?", canvas.id(), resourceId),
        "Blob 释放失败必须回滚资源行删除");
    assertEquals(resourceId, ownedResourceId(canvas.id(), nodeId));
  }

  private Graph addGraph(CanvasFunctionRunStatus status) {
    // Blob 写入端已为两条资源边各持有一次引用。
    UUID blobId = insertBlob("ACTIVE", 2L);
    CanvasDocument canvas = documents.create(UUID.randomUUID(), "graph");
    UUID source = UUID.randomUUID();
    UUID function = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    long revision =
        apply(
            canvas,
            new CanvasCommand.CreateGroup(group, "group", TRANSFORM),
            new CanvasCommand.CreateNode(
                source,
                "source",
                TRANSFORM,
                List.of(new CanvasResourceInput.Blob("a.png", blobId))),
            new CanvasCommand.CreateNode(
                function,
                "function",
                TRANSFORM,
                List.of(new CanvasResourceInput.Blob("b.png", blobId))),
            new CanvasCommand.SetNodeGroup(source, null, group),
            new CanvasCommand.SetNodeFunction(
                function,
                null,
                new CanvasFunction(
                    "video.generate",
                    CanvasJson.parseObject(
                        "{\"source\":{\"type\":\"resource\",\"nodeId\":\""
                            + source
                            + "\",\"index\":0}}"))));
    UUID pinned = ownedResourceId(canvas.id(), source);
    jdbc.update(
        """
        insert into canvas_function_run (node_id, request_id, status, state_json, error)
        values (?, ?, ?, cast(? as jsonb), ?)
        """,
        function,
        request,
        status.name(),
        "{\"stage\":\"" + status.name() + "\"}",
        status == CanvasFunctionRunStatus.UNKNOWN ? "external outcome unknown" : null);
    jdbc.update(
        """
        insert into canvas_function_resource_pin (canvas_id, node_id, request_id, role, resource_id)
        values (?, ?, ?, 'INPUT', ?)
        """,
        canvas.id(),
        function,
        request,
        pinned);
    if (status != CanvasFunctionRunStatus.UNKNOWN) {
      // 也验证终态节点残留的 Work 不会脱离画布独立存在。
      jdbc.update(
          """
          insert into durable_work (target_type, target_id, available_at, wake_version)
          values ('CANVAS_RUN', ?, statement_timestamp(), 1)
          """,
          function);
    }
    return new Graph(canvas.id(), function, blobId, revision);
  }

  private void assertGraphIntact(Graph graph, CanvasFunctionRunStatus status) {
    assertEquals(
        List.of(1L, 2L, 1L, 2L, 1L, 1L, 1L, status == CanvasFunctionRunStatus.UNKNOWN ? 0L : 1L),
        graphRows(graph));
    assertEquals(
        1L,
        count("canvas_document", "id = ? and revision = ?", graph.canvasId(), graph.revision()));
    assertEquals(
        1L,
        count("canvas_function_run", "node_id = ? and status = ?", graph.nodeId(), status.name()));
    assertEquals(2L, refCount(graph.blobId()));
    assertEquals("ACTIVE", blobState(graph.blobId()));
  }

  private List<Long> graphRows(Graph graph) {
    return List.of(
        count("canvas_document", "id = ?", graph.canvasId()),
        count("canvas_node", "canvas_id = ?", graph.canvasId()),
        count("canvas_group", "canvas_id = ?", graph.canvasId()),
        count("canvas_resource", "canvas_id = ?", graph.canvasId()),
        count("canvas_function_run", "node_id = ?", graph.nodeId()),
        count("canvas_function_resource_pin", "canvas_id = ?", graph.canvasId()),
        count("canvas_command_dedup", "canvas_id = ?", graph.canvasId()),
        count("durable_work", "target_type = 'CANVAS_RUN' and target_id = ?", graph.nodeId()));
  }

  private record Graph(UUID canvasId, UUID nodeId, UUID blobId, long revision) {}

  /** 创建和删除必须被接受；若返回冲突，类型断言立即失败。 */
  private long apply(CanvasDocument canvas, CanvasCommand... commands) {
    CanvasCommandResult result =
        commandService.applyCommandsFromUploads(
            canvas.id(), UUID.randomUUID(), List.of(commands), USER_ID);
    return assertInstanceOf(CanvasCommandResult.Accepted.class, result).patch().revision();
  }

  private UUID ownedResourceId(UUID canvasId, UUID nodeId) {
    return jdbc.queryForObject(
        "select id from canvas_resource where canvas_id = ? and owner_node_id = ?",
        UUID.class,
        canvasId,
        nodeId);
  }

  private UUID insertBlob(String state, long refCount) {
    UUID blobId = UUID.randomUUID();
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state)"
            + " values (?, ?, ?, ?, ?, ?)",
        blobId,
        "0".repeat(64),
        4L,
        "image/png",
        refCount,
        state);
    return blobId;
  }

  private long refCount(UUID blobId) {
    Long value =
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId);
    return value == null ? 0L : value;
  }

  private String blobState(UUID blobId) {
    return jdbc.queryForObject("select state from storage_blob where id = ?", String.class, blobId);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CallerConfiguration {
    @Bean
    TransactionalCanvasCaller transactionalCanvasCaller(CanvasDocuments documents) {
      return new TransactionalCanvasCaller(documents);
    }
  }

  static class TransactionalCanvasCaller {
    private final CanvasDocuments documents;

    TransactionalCanvasCaller(CanvasDocuments documents) {
      this.documents = documents;
    }

    @Transactional
    public CanvasDocument createAndCommit(String title) {
      return documents.create(UUID.randomUUID(), title);
    }

    @Transactional
    public UUID createAndRollback(String title) {
      UUID id = documents.create(UUID.randomUUID(), title).id();
      throw new RollbackAfterCreate(id);
    }

    @Transactional
    public void deleteAndRollback(UUID canvasId, long revision) {
      documents.delete(canvasId, revision);
      throw new RollbackAfterDelete();
    }
  }

  static class RollbackAfterCreate extends RuntimeException {
    private final UUID canvasId;

    RollbackAfterCreate(UUID canvasId) {
      this.canvasId = canvasId;
    }
  }

  static class RollbackAfterDelete extends RuntimeException {}

  private long count(String table, String where, Object... args) {
    Long value =
        jdbc.queryForObject("select count(*) from " + table + " where " + where, Long.class, args);
    return value == null ? 0L : value;
  }
}

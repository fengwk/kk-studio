package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasStore.CommandDedup;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** {@link PostgresqlCanvasStore} 在真实 PostgreSQL 上的 document/node/group/dedup 端口契约。 */
class PostgresqlCanvasStoreIntegrationTest extends PostgresCanvasInfraTestSupport {

  /** revision 只是同步位置：CAS 只接受匹配的当前值，缺失 document 的所有操作保持缺失语义，不隐式创建行。 */
  @Test
  void documentRevisionCasKeepsMissingSemantics() {
    UUID canvasId = UUID.randomUUID();
    assertTrue(canvasStore.findDocument(canvasId).isEmpty());
    assertTrue(canvasStore.lockDocument(canvasId).isEmpty());
    assertTrue(canvasStore.lockDocumentForKeyShare(canvasId).isEmpty());
    assertFalse(canvasStore.deleteDocument(canvasId));

    CanvasDocument created = canvasStore.addDocument(canvasId, "canvas");
    assertEquals(0L, created.revision());
    assertEquals(created, canvasStore.findDocument(canvasId).orElseThrow());
    assertEquals(List.of(created), canvasStore.listDocuments());
    assertEquals(
        created, transactions.execute(status -> canvasStore.lockDocument(canvasId).orElseThrow()));
    assertEquals(
        created,
        transactions.execute(
            status -> canvasStore.lockDocumentForKeyShare(canvasId).orElseThrow()));

    assertFalse(canvasStore.advanceRevision(canvasId, 1L, 2L));
    assertTrue(canvasStore.advanceRevision(canvasId, 0L, 1L));
    assertFalse(canvasStore.advanceRevision(canvasId, 0L, 2L));
    assertEquals(1L, canvasStore.findDocument(canvasId).orElseThrow().revision());
    assertTrue(canvasStore.deleteDocument(canvasId));
    assertTrue(canvasStore.findDocument(canvasId).isEmpty());
  }

  /** FOR UPDATE 必须真实持有行锁：另一连接的 CAS 在 PostgreSQL {@code lock_timeout} 下被确定性拒绝，而不是由进程内 mock 模拟。 */
  @Test
  void documentRowLockSerializesConcurrentWriters() throws Exception {
    UUID canvasId = addDocument();
    CountDownLatch locked = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> holder =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        canvasStore.lockDocument(canvasId).orElseThrow();
                        locked.countDown();
                        try {
                          Thread.sleep(500L);
                        } catch (InterruptedException error) {
                          Thread.currentThread().interrupt();
                        }
                      }));
      assertTrue(locked.await(10L, TimeUnit.SECONDS));
      try (Connection other = newConnection();
          Statement statement = other.createStatement()) {
        statement.execute("set lock_timeout = '100ms'");
        assertThrows(
            SQLException.class,
            () ->
                statement.executeQuery(
                    "select id from canvas_document where id = '" + canvasId + "' for update"));
      }
      holder.get(10L, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    assertTrue(canvasStore.advanceRevision(canvasId, 0L, 1L));
  }

  /** 节点行是目标模型：名称唯一键按兼容折叠比较，Function 以 base shape JSONB 往返。 */
  @Test
  void nodeRowsCarryFunctionAndCompatibilityFoldedNameKey() {
    UUID canvasId = addDocument();
    NodeFixture functionNode = addFunctionNode(canvasId, "video.generate");
    NodeFixture textNode = addTextNode(canvasId, "body");
    canvasStore.updateNode(
        functionNode.record(
            new CanvasFunction(
                "video.generate", CanvasJson.parseObject("{\"b\":2,\"a\":[1,true,null]}"))));

    assertEquals(
        List.of(functionNode.nodeId, textNode.nodeId).stream().sorted().toList(),
        canvasStore.listNodes(canvasId).stream().map(NodeRecord::id).sorted().toList());
    NodeRecord stored = canvasStore.findNode(canvasId, functionNode.nodeId).orElseThrow();
    // JSONB 可能归一化对象键顺序，语义相等与文本往返都必须稳定。
    assertEquals(CanvasJson.parseObject("{\"a\":[1,true,null],\"b\":2}"), stored.function().args());
    assertEquals(stored.function().args(), CanvasJson.parseObject(stored.function().argsJson()));

    NodeRecord moved =
        new NodeRecord(
            functionNode.nodeId,
            canvasId,
            "renamed",
            new CanvasTransform(1, 2, 640, 480),
            null,
            stored.function());
    assertTrue(canvasStore.updateNode(moved));
    assertEquals(canvasStore.findNode(canvasId, functionNode.nodeId).orElseThrow(), moved);
    assertEquals(
        moved,
        transactions.execute(
            status -> canvasStore.lockNode(canvasId, functionNode.nodeId).orElseThrow()));

    assertThrows(
        DataAccessException.class,
        () ->
            canvasStore.updateNode(textNode.renamed(moved.name().toUpperCase(Locale.ROOT), null)));
    assertTrue(canvasStore.deleteNode(canvasId, functionNode.nodeId));
    assertTrue(canvasStore.findNode(canvasId, functionNode.nodeId).isEmpty());
  }

  /** 分组是节点归属的父行：更新全行、成员存在时拒绝删除，成员清空后才能删除。 */
  @Test
  void groupRowsRestrictNodeMembership() {
    UUID canvasId = addDocument();
    NodeFixture node = addTextNode(canvasId, "body");
    UUID groupId = UUID.randomUUID();
    CanvasGroup group =
        new CanvasGroup(groupId, canvasId, "group", new CanvasTransform(0, 0, 10, 10));
    canvasStore.addGroup(group);

    assertEquals(group, canvasStore.findGroup(canvasId, groupId).orElseThrow());
    assertEquals(List.of(group), canvasStore.listGroups(canvasId));
    assertTrue(canvasStore.findGroup(UUID.randomUUID(), groupId).isEmpty());

    CanvasGroup renamed =
        new CanvasGroup(groupId, canvasId, "renamed", new CanvasTransform(5, 5, 20, 20));
    assertTrue(canvasStore.updateGroup(renamed));
    assertEquals(renamed, canvasStore.findGroup(canvasId, groupId).orElseThrow());

    assertTrue(canvasStore.updateNode(node.record(groupId, null)));
    assertEquals(groupId, canvasStore.findNode(canvasId, node.nodeId).orElseThrow().groupId());
    assertThrows(DataAccessException.class, () -> canvasStore.deleteGroup(canvasId, groupId));

    assertTrue(canvasStore.updateNode(node.record(null)));
    assertTrue(canvasStore.deleteGroup(canvasId, groupId));
    assertTrue(canvasStore.listGroups(canvasId).isEmpty());
  }

  /** 去重行记录请求指纹与首次接受位置，按画布整体清理。 */
  @Test
  void commandDedupRecordsAcceptedRevision() {
    UUID canvasId = addDocument();
    UUID idempotencyKey = UUID.randomUUID();
    CommandDedup dedup = new CommandDedup(canvasId, idempotencyKey, "0".repeat(64), 3L);

    assertTrue(canvasStore.findCommandDedup(canvasId, idempotencyKey).isEmpty());
    canvasStore.addCommandDedup(dedup);
    assertEquals(dedup, canvasStore.findCommandDedup(canvasId, idempotencyKey).orElseThrow());
    assertTrue(canvasStore.findCommandDedup(UUID.randomUUID(), idempotencyKey).isEmpty());
    assertEquals(1, canvasStore.deleteCommandDedupByCanvas(canvasId));
    assertTrue(canvasStore.findCommandDedup(canvasId, idempotencyKey).isEmpty());
  }
}

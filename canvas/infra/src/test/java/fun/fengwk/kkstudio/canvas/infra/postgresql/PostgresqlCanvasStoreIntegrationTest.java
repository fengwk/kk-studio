package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasLink;
import fun.fengwk.kkstudio.canvas.CanvasStore.CommandDedup;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** {@link PostgresqlCanvasStore} 在真实 PostgreSQL 上的完整 graph/document 端口契约。 */
class PostgresqlCanvasStoreIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * Document CRUD/CAS 必须保留缺失语义；FOR UPDATE 必须真实持有行锁，使另一事务的 CAS 被 PostgreSQL lock_timeout
   * 确定性拒绝，而不是由进程内 mock 模拟。
   */
  @Test
  void documentCrudCasAndForUpdateLock() throws Exception {
    UUID canvasId = UUID.randomUUID();
    assertTrue(canvasStore.findDocument(canvasId).isEmpty());
    assertTrue(canvasStore.lockDocument(canvasId).isEmpty());
    assertTrue(canvasStore.lockDocumentForKeyShare(canvasId).isEmpty());
    assertFalse(canvasStore.deleteDocument(canvasId));

    CanvasDocument created = canvasStore.addDocument(canvasId, "locked canvas");
    assertEquals(0L, created.version());
    assertEquals(created, canvasStore.findDocument(canvasId).orElseThrow());
    assertEquals(List.of(created), canvasStore.listDocuments());
    assertEquals(
        created, transactions.execute(status -> canvasStore.lockDocument(canvasId).orElseThrow()));
    assertEquals(
        created,
        transactions.execute(
            status -> canvasStore.lockDocumentForKeyShare(canvasId).orElseThrow()));

    assertFalse(canvasStore.advanceDocumentVersion(canvasId, 1L, 2L));
    assertTrue(canvasStore.advanceDocumentVersion(canvasId, 0L, 1L));
    assertFalse(canvasStore.advanceDocumentVersion(canvasId, 0L, 2L));
    assertEquals(1L, canvasStore.findDocument(canvasId).orElseThrow().version());

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> holder =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        assertTrue(canvasStore.lockDocument(canvasId).isPresent());
                        locked.countDown();
                        await(release);
                      }));
      assertTrue(locked.await(10, TimeUnit.SECONDS));

      DataAccessException blocked =
          assertThrows(
              DataAccessException.class,
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        jdbc.execute("set local lock_timeout = '200ms'");
                        canvasStore.advanceDocumentVersion(canvasId, 1L, 2L);
                      }));
      assertEquals("55P03", postgresError(blocked).getSQLState());

      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
    }

    assertTrue(canvasStore.advanceDocumentVersion(canvasId, 1L, 2L));
    assertTrue(canvasStore.deleteDocument(canvasId));
    assertFalse(canvasStore.deleteDocument(canvasId));
    assertTrue(canvasStore.listDocuments().isEmpty());
  }

  /** Node/Group 的全部更新返回值、成员移动/挂接规则与删除幂等性必须来自真实 SQL 受影响行数。 */
  @Test
  void nodeAndGroupCrudReturnsExactDatabaseSemantics() throws JsonProcessingException {
    UUID canvasId = addDocument();
    UUID missingId = UUID.randomUUID();
    CanvasGroup firstGroup =
        new CanvasGroup(UUID.randomUUID(), canvasId, "first", new CanvasTransform(1, 2, 400, 300));
    CanvasGroup secondGroup =
        new CanvasGroup(UUID.randomUUID(), canvasId, "second", new CanvasTransform(5, 6, 500, 350));
    canvasStore.addGroup(firstGroup);
    canvasStore.addGroup(secondGroup);
    assertEquals(
        List.of(firstGroup, secondGroup).stream().map(CanvasGroup::id).sorted().toList(),
        canvasStore.listGroups(canvasId).stream().map(CanvasGroup::id).sorted().toList());
    assertEquals(firstGroup, canvasStore.findGroup(canvasId, firstGroup.id()).orElseThrow());
    assertTrue(canvasStore.findGroup(canvasId, missingId).isEmpty());

    NodeRecord functionNode = addNode(canvasId, true);
    NodeRecord ordinaryNode = addNode(canvasId, false);
    assertEquals(
        List.of(functionNode.id(), ordinaryNode.id()).stream().sorted().toList(),
        canvasStore.listNodes(canvasId).stream().map(NodeRecord::id).sorted().toList());
    assertNodeEquivalent(
        functionNode, canvasStore.findNode(canvasId, functionNode.id()).orElseThrow());
    assertTrue(canvasStore.findNode(canvasId, missingId).isEmpty());
    assertNodeEquivalent(
        functionNode,
        transactions.execute(
            status -> canvasStore.lockNode(canvasId, functionNode.id()).orElseThrow()));
    assertEquals(
        Boolean.TRUE,
        transactions.execute(status -> canvasStore.lockNode(canvasId, missingId).isEmpty()));

    NodeRecord transformed = functionNode.withTransform(new CanvasTransform(100, 200, 640, 480));
    assertTrue(canvasStore.updateNodeTransform(transformed));
    assertFalse(
        canvasStore.updateNodeTransform(
            new NodeRecord(
                missingId, canvasId, "missing", transformed.transform(), null, null, null)));
    assertTrue(canvasStore.renameNode(canvasId, functionNode.id(), "renamed"));
    assertFalse(canvasStore.renameNode(canvasId, missingId, "missing"));
    assertTrue(
        canvasStore.updateNodeFunction(
            canvasId, functionNode.id(), "updated-model", "{\"parameters\":{}}"));
    assertFalse(
        canvasStore.updateNodeFunction(
            canvasId, ordinaryNode.id(), "not-a-function", "{\"parameters\":{}}"));

    assertTrue(
        canvasStore.attachNodeToGroupIfUngrouped(canvasId, functionNode.id(), firstGroup.id()));
    assertFalse(
        canvasStore.attachNodeToGroupIfUngrouped(canvasId, functionNode.id(), secondGroup.id()));
    assertTrue(
        canvasStore.attachNodeToGroupIfUngrouped(canvasId, ordinaryNode.id(), firstGroup.id()));
    assertEquals(2, canvasStore.moveGroupNodes(canvasId, firstGroup.id(), 3.5, -4.5));
    NodeRecord moved = canvasStore.findNode(canvasId, functionNode.id()).orElseThrow();
    assertEquals(103.5, moved.transform().x());
    assertEquals(195.5, moved.transform().y());

    assertFalse(canvasStore.detachNodeFromGroup(canvasId, secondGroup.id(), functionNode.id()));
    assertTrue(canvasStore.detachNodeFromGroup(canvasId, firstGroup.id(), functionNode.id()));
    assertEquals(1, canvasStore.detachAllNodesFromGroup(canvasId, firstGroup.id()));
    assertEquals(0, canvasStore.detachAllNodesFromGroup(canvasId, firstGroup.id()));

    assertTrue(
        canvasStore.attachNodeToGroupIfUngrouped(canvasId, functionNode.id(), secondGroup.id()));
    assertTrue(
        canvasStore.attachNodeToGroupIfUngrouped(canvasId, ordinaryNode.id(), secondGroup.id()));
    assertEquals(2, canvasStore.detachAllNodesFromGroup(canvasId, secondGroup.id()));

    CanvasGroup movedGroup =
        new CanvasGroup(
            firstGroup.id(),
            canvasId,
            firstGroup.title(),
            new CanvasTransform(
                90, 80, firstGroup.transform().width(), firstGroup.transform().height()));
    assertTrue(canvasStore.moveGroup(movedGroup));
    assertFalse(
        canvasStore.moveGroup(
            new CanvasGroup(missingId, canvasId, "missing", new CanvasTransform(1, 1, 10, 10))));
    assertTrue(canvasStore.renameGroup(canvasId, firstGroup.id(), "renamed group"));
    assertFalse(canvasStore.renameGroup(canvasId, missingId, "missing"));
    CanvasGroup persistedGroup = canvasStore.findGroup(canvasId, firstGroup.id()).orElseThrow();
    assertEquals("renamed group", persistedGroup.title());
    assertEquals(90, persistedGroup.transform().x());
    assertEquals(80, persistedGroup.transform().y());

    assertTrue(canvasStore.deleteNode(canvasId, functionNode.id()));
    assertFalse(canvasStore.deleteNode(canvasId, functionNode.id()));
    assertTrue(canvasStore.deleteNode(canvasId, ordinaryNode.id()));
    assertTrue(canvasStore.deleteGroup(canvasId, firstGroup.id()));
    assertFalse(canvasStore.deleteGroup(canvasId, firstGroup.id()));
    assertTrue(canvasStore.deleteGroup(canvasId, secondGroup.id()));
    assertTrue(canvasStore.listNodes(canvasId).isEmpty());
    assertTrue(canvasStore.listGroups(canvasId).isEmpty());
  }

  /** Link 的存在、定点删除、按节点删除和按 Canvas 删除必须分别返回 PostgreSQL 的精确结果。 */
  @Test
  void linkLifecycleCoversEveryDeleteShape() {
    UUID canvasId = addDocument();
    NodeRecord sourceA = addNode(canvasId, false);
    NodeRecord sourceB = addNode(canvasId, false);
    NodeRecord target = addNode(canvasId, true);
    CanvasLink first = new CanvasLink(canvasId, sourceA.id(), target.id());
    CanvasLink second = new CanvasLink(canvasId, sourceB.id(), target.id());

    canvasStore.addLink(first);
    canvasStore.addLink(second);
    assertEquals(
        List.of(first, second).stream().map(CanvasLink::sourceNodeId).sorted().toList(),
        canvasStore.listLinks(canvasId).stream().map(CanvasLink::sourceNodeId).sorted().toList());
    assertTrue(canvasStore.linkExists(canvasId, sourceA.id(), target.id()));
    assertFalse(canvasStore.linkExists(canvasId, target.id(), sourceA.id()));
    assertTrue(canvasStore.deleteLink(canvasId, sourceA.id(), target.id()));
    assertFalse(canvasStore.deleteLink(canvasId, sourceA.id(), target.id()));
    assertEquals(1, canvasStore.deleteLinksByNode(canvasId, target.id()));
    assertEquals(0, canvasStore.deleteLinksByNode(canvasId, target.id()));

    canvasStore.addLink(first);
    canvasStore.addLink(second);
    assertEquals(2, canvasStore.deleteLinksByCanvas(canvasId));
    assertEquals(0, canvasStore.deleteLinksByCanvas(canvasId));
    assertTrue(canvasStore.listLinks(canvasId).isEmpty());
  }

  /** Command dedup 的缺失、持久化投影与聚合清理行数必须保持端口语义。 */
  @Test
  void commandDedupLifecycle() {
    UUID canvasId = addDocument();
    UUID commandId = UUID.randomUUID();
    CommandDedup dedup = new CommandDedup(canvasId, commandId, "a".repeat(64));

    assertTrue(canvasStore.findCommandDedup(canvasId, commandId).isEmpty());
    canvasStore.addCommandDedup(dedup);
    assertEquals(dedup, canvasStore.findCommandDedup(canvasId, commandId).orElseThrow());
    assertEquals(1, canvasStore.deleteCommandDedupByCanvas(canvasId));
    assertEquals(0, canvasStore.deleteCommandDedupByCanvas(canvasId));
    assertTrue(canvasStore.findCommandDedup(canvasId, commandId).isEmpty());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for lock test coordination");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  /** PostgreSQL jsonb 会规范化对象成员间的空白；节点回读契约应逐字段一致，并按 JSON 结构而非输入字符串格式比较 Function 配置。 */
  private static void assertNodeEquivalent(NodeRecord expected, NodeRecord actual)
      throws JsonProcessingException {
    assertEquals(expected.id(), actual.id());
    assertEquals(expected.canvasId(), actual.canvasId());
    assertEquals(expected.name(), actual.name());
    assertEquals(expected.transform(), actual.transform());
    assertEquals(expected.groupId(), actual.groupId());
    assertEquals(expected.modelKey(), actual.modelKey());
    if (expected.functionConfigJson() == null || actual.functionConfigJson() == null) {
      assertEquals(expected.functionConfigJson(), actual.functionConfigJson());
    } else {
      assertEquals(
          OBJECT_MAPPER.readTree(expected.functionConfigJson()),
          OBJECT_MAPPER.readTree(actual.functionConfigJson()));
    }
  }

  private static PSQLException postgresError(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof PSQLException postgres) {
        return postgres;
      }
      current = current.getCause();
    }
    assertNotNull(current, "expected PostgreSQL cause");
    throw new AssertionError("unreachable");
  }
}

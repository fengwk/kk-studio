package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult.Accepted;
import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasResourceInput;
import fun.fengwk.kkstudio.canvas.CanvasTransform;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@code canvas_revision} 事务通知：只有 document 插入或 revision 真实前进才在提交时投递。
 *
 * <p>测试 fixture 已删除旧 Schema 触发器，因此这里的断言只可能由 Java 写入口（{@link PostgresqlCanvasStore} 的 {@code
 * addDocument} / {@code advanceRevision}）满足。回滚与未提交必须不可见，CAS 失败与未变更必须静默。
 */
class CanvasRevisionNotificationIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final CanvasTransform TRANSFORM = new CanvasTransform(0, 0, 10, 10);

  @Autowired private CanvasCommandService commandService;

  /** 插入 document 必须在提交时投递 {@code canvasId:0}。 */
  @Test
  void documentInsertNotifiesRevisionZeroOnCommit() throws Exception {
    UUID canvasId = UUID.randomUUID();
    try (Connection listener = listenOn(PostgresqlCanvasChangeNotifier.REVISION_CHANNEL)) {
      canvasStore.addDocument(canvasId, "canvas");
      assertRevisionNotification(listener, canvasId + ":0");
    }
  }

  /** revision 真实前进才投递新位置；CAS 失败与写入相同值都保持静默。 */
  @Test
  void revisionAdvanceNotifiesOnlyOnRealChange() throws Exception {
    UUID canvasId = addDocument();
    try (Connection listener = listenOn(PostgresqlCanvasChangeNotifier.REVISION_CHANNEL)) {
      assertTrue(canvasStore.advanceRevision(canvasId, 0L, 1L));
      assertRevisionNotification(listener, canvasId + ":1");

      assertFalse(canvasStore.advanceRevision(canvasId, 0L, 2L));
      assertNoNotification(listener);

      assertTrue(canvasStore.advanceRevision(canvasId, 1L, 1L));
      assertNoNotification(listener);
    }
  }

  /** 删除 document 不是 revision 前进，不得伪造 revision 通知。 */
  @Test
  void documentDeletionDoesNotFabricateRevision() throws Exception {
    UUID canvasId = addDocument();
    try (Connection listener = listenOn(PostgresqlCanvasChangeNotifier.REVISION_CHANNEL)) {
      assertTrue(canvasStore.deleteDocument(canvasId));
      assertNoNotification(listener);
    }
  }

  /** 写入回滚时 PostgreSQL 不得投递通知，行事实也不能部分可见。 */
  @Test
  void rolledBackDocumentWriteDoesNotNotify() throws Exception {
    UUID canvasId = UUID.randomUUID();
    try (Connection listener = listenOn(PostgresqlCanvasChangeNotifier.REVISION_CHANNEL)) {
      assertThrows(
          IllegalStateException.class,
          () ->
              transactions.executeWithoutResult(
                  ignored -> {
                    canvasStore.addDocument(canvasId, "canvas");
                    canvasStore.advanceRevision(canvasId, 0L, 1L);
                    throw new IllegalStateException("rollback");
                  }));
      assertNoNotification(listener);
      assertTrue(canvasStore.findDocument(canvasId).isEmpty());
    }
  }

  /** 未提交的 revision 前进对其他连接不可见；只有提交后才投递通知。 */
  @Test
  void uncommittedRevisionIsNotVisibleUntilCommit() throws Exception {
    UUID canvasId = addDocument();
    CountDownLatch written = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection listener = listenOn(PostgresqlCanvasChangeNotifier.REVISION_CHANNEL)) {
      Future<?> future =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      ignored -> {
                        canvasStore.advanceRevision(canvasId, 0L, 1L);
                        written.countDown();
                        await(release);
                      }));
      assertTrue(written.await(10L, TimeUnit.SECONDS));
      assertNoNotification(listener);

      release.countDown();
      future.get(10L, TimeUnit.SECONDS);
      assertRevisionNotification(listener, canvasId + ":1");
    } finally {
      executor.shutdownNow();
    }
  }

  /** 命令服务创建与真实变更经同一写入口通知；无变化批不推进 revision，必须静默。 */
  @Test
  void commandServiceNotifiesOnCreateAndRealRevisionChange() throws Exception {
    try (Connection listener = listenOn(PostgresqlCanvasChangeNotifier.REVISION_CHANNEL)) {
      CanvasDocument document = commandService.createCanvas("canvas");
      UUID canvasId = document.id();
      assertRevisionNotification(listener, canvasId + ":0");

      UUID nodeId = UUID.randomUUID();
      assertInstanceOf(
          Accepted.class,
          commandService.applyCommands(
              canvasId,
              UUID.randomUUID(),
              List.of(
                  new CanvasCommand.CreateNode(
                      nodeId,
                      "node",
                      TRANSFORM,
                      List.of(new CanvasResourceInput.Text("text", "body"))))));
      assertRevisionNotification(listener, canvasId + ":1");

      assertInstanceOf(
          Accepted.class,
          commandService.applyCommands(
              canvasId,
              UUID.randomUUID(),
              List.of(new CanvasCommand.RenameNode(nodeId, "node", "node"))));
      assertNoNotification(listener);
    }
  }

  private static void assertRevisionNotification(Connection listener, String payload)
      throws Exception {
    PGNotification[] notifications = pollNotifications(listener, 5_000);
    assertEquals(1, notifications.length);
    assertEquals(PostgresqlCanvasChangeNotifier.REVISION_CHANNEL, notifications[0].getName());
    assertEquals(payload, notifications[0].getParameter());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10L, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for latch");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for latch", error);
    }
  }
}

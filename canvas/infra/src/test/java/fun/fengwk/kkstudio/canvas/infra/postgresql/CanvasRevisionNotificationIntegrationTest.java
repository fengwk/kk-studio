package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult.Accepted;
import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasResourceInput;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.notification.CanvasNotifications;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;
import fun.fengwk.kkstudio.share.notification.VersionHint;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@code canvas.revision} 事务通知：只有 document 插入或 revision 真实前进才在提交时投递。
 *
 * <p>通知由 Java 写入口（{@link PostgresqlCanvasStore} 的 {@code addDocument} / {@code
 * advanceRevision}）在事务内发布。回滚与未提交必须不可见，CAS 失败与未变更必须静默。
 */
class CanvasRevisionNotificationIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final CanvasTransform TRANSFORM = new CanvasTransform(0, 0, 10, 10);

  @Autowired private CanvasCommandService commandService;

  /** 插入 document 必须在提交时投递 {@code canvasId:0}。 */
  @Test
  void documentInsertNotifiesRevisionZeroOnCommit() throws Exception {
    UUID canvasId = UUID.randomUUID();
    BlockingQueue<VersionHint> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription sub =
        bus.subscribe(CanvasNotifications.REVISION, notifications::add, () -> {})) {
      canvasStore.addDocument(canvasId, "canvas");
      assertRevisionNotification(notifications, canvasId, 0L);
    }
  }

  /** revision 真实前进才投递新位置；CAS 失败与写入相同值都保持静默。 */
  @Test
  void revisionAdvanceNotifiesOnlyOnRealChange() throws Exception {
    UUID canvasId = addDocument();
    BlockingQueue<VersionHint> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription sub =
        bus.subscribe(CanvasNotifications.REVISION, notifications::add, () -> {})) {
      assertTrue(canvasStore.advanceRevision(canvasId, 0L, 1L));
      assertRevisionNotification(notifications, canvasId, 1L);

      assertFalse(canvasStore.advanceRevision(canvasId, 0L, 2L));
      assertNoNotification(notifications);

      assertTrue(canvasStore.advanceRevision(canvasId, 1L, 1L));
      assertNoNotification(notifications);
    }
  }

  /** 删除 document 不是 revision 前进，不得伪造 revision 通知。 */
  @Test
  void documentDeletionDoesNotFabricateRevision() throws Exception {
    UUID canvasId = addDocument();
    BlockingQueue<VersionHint> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription sub =
        bus.subscribe(CanvasNotifications.REVISION, notifications::add, () -> {})) {
      assertTrue(canvasStore.deleteDocument(canvasId));
      assertNoNotification(notifications);
    }
  }

  /** 写入回滚时 PostgreSQL 不得投递通知，行事实也不能部分可见。 */
  @Test
  void rolledBackDocumentWriteDoesNotNotify() throws Exception {
    UUID canvasId = UUID.randomUUID();
    BlockingQueue<VersionHint> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription sub =
        bus.subscribe(CanvasNotifications.REVISION, notifications::add, () -> {})) {
      assertThrows(
          IllegalStateException.class,
          () ->
              transactions.executeWithoutResult(
                  ignored -> {
                    canvasStore.addDocument(canvasId, "canvas");
                    canvasStore.advanceRevision(canvasId, 0L, 1L);
                    throw new IllegalStateException("rollback");
                  }));
      assertNoNotification(notifications);
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
    BlockingQueue<VersionHint> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription sub =
        bus.subscribe(CanvasNotifications.REVISION, notifications::add, () -> {})) {
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
      assertNoNotification(notifications);

      release.countDown();
      future.get(10L, TimeUnit.SECONDS);
      assertRevisionNotification(notifications, canvasId, 1L);
    } finally {
      executor.shutdownNow();
    }
  }

  /** 命令服务创建与真实变更经同一写入口通知；无变化批不推进 revision，必须静默。 */
  @Test
  void commandServiceNotifiesOnCreateAndRealRevisionChange() throws Exception {
    BlockingQueue<VersionHint> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription sub =
        bus.subscribe(CanvasNotifications.REVISION, notifications::add, () -> {})) {
      CanvasDocument document = commandService.createCanvas("canvas");
      UUID canvasId = document.id();
      assertRevisionNotification(notifications, canvasId, 0L);

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
      assertRevisionNotification(notifications, canvasId, 1L);

      assertInstanceOf(
          Accepted.class,
          commandService.applyCommands(
              canvasId,
              UUID.randomUUID(),
              List.of(new CanvasCommand.RenameNode(nodeId, "node", "node"))));
      assertNoNotification(notifications);
    }
  }

  private static void assertRevisionNotification(
      BlockingQueue<VersionHint> queue, UUID expectedCanvasId, long expectedRevision)
      throws Exception {
    VersionHint hint = queue.poll(5, TimeUnit.SECONDS);
    assertNotNull(hint, "expected revision notification but timed out");
    assertEquals(new VersionHint(expectedCanvasId, expectedRevision), hint);
  }

  private static void assertNoNotification(BlockingQueue<VersionHint> queue) throws Exception {
    VersionHint hint = queue.poll(300, TimeUnit.MILLISECONDS);
    assertNull(hint, () -> "unexpected notification: " + hint);
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

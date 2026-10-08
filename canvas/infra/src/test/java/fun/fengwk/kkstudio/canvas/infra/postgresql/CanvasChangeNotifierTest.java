package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.share.notification.NotificationBus;

import java.util.UUID;

/** 事务守卫必须在没有真实事务时拒绝发布，避免「事实已提交、通知另开连接发送」的失败窗口。 */
class CanvasChangeNotifierTest {

  private final CanvasChangeNotifier notifier =
      new CanvasChangeNotifier(mock(JdbcTemplate.class), mock(NotificationBus.class));

  @Test
  void rejectsNotificationWithoutActiveTransaction() {
    assertThrows(
        IllegalStateException.class, () -> notifier.revisionChanged(UUID.randomUUID(), 1L));
    assertThrows(
        IllegalStateException.class, () -> notifier.functionWorkChanged(UUID.randomUUID()));
  }
}

package fun.fengwk.kkstudio.platform.harness.notification;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 统一 PostgreSQL LISTEN 循环的一条 Harness 通知绑定：固定 channel 名与投递、重连恢复回调。
 *
 * <p>组合根只按绑定装配监听，不需要知道 channel 字符串或 payload 协议；两个回调都不得阻塞，且必须是自己隔离失败边界的入口。
 */
public record HarnessNotificationBinding(
    String channel, Consumer<String> onNotification, Runnable onResync) {

  public HarnessNotificationBinding {
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(onNotification, "onNotification");
    Objects.requireNonNull(onResync, "onResync");
  }
}

package fun.fengwk.kkstudio.web.events.postgresql;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 一个固定 PostgreSQL channel 的不可变通知处理契约。
 *
 * <p>Channel 只允许无需引用的 ASCII PostgreSQL identifier，并在保存前完成严格校验，确保 LISTEN SQL 只能拼接可信标识符。
 */
public final class PostgresqlNotificationHandler {

  private static final int MAX_IDENTIFIER_BYTES = 63;
  private static final Pattern CHANNEL_PATTERN = Pattern.compile("[a-z_][a-z0-9_]*");

  private final String channel;
  private final Consumer<String> notificationCallback;
  private final Runnable resyncCallback;

  public PostgresqlNotificationHandler(
      String channel, Consumer<String> notificationCallback, Runnable resyncCallback) {
    this.channel = requireChannel(channel);
    this.notificationCallback =
        Objects.requireNonNull(notificationCallback, "notificationCallback");
    this.resyncCallback = Objects.requireNonNull(resyncCallback, "resyncCallback");
  }

  public String channel() {
    return channel;
  }

  public void onNotification(String payload) {
    notificationCallback.accept(payload);
  }

  public void onResync() {
    resyncCallback.run();
  }

  private static String requireChannel(String channel) {
    Objects.requireNonNull(channel, "channel");
    if (!CHANNEL_PATTERN.matcher(channel).matches()
        || channel.getBytes(StandardCharsets.US_ASCII).length > MAX_IDENTIFIER_BYTES) {
      throw new IllegalArgumentException("invalid PostgreSQL notification channel: " + channel);
    }
    return channel;
  }
}

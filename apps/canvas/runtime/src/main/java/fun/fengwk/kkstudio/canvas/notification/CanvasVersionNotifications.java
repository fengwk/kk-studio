package fun.fengwk.kkstudio.canvas.notification;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasQueryService;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.platform.events.SourceSubscribed;
import fun.fengwk.kkstudio.platform.notification.VersionNotificationHub;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Canvas revision 通知的生产方入口：独占 {@code canvas_revision} channel 与 {@code canvasId:revision} payload
 * 协议。
 *
 * <p>通用的先注册再读权威 revision、畸形 payload resync 与进程内 fan-out 由 {@link VersionNotificationHub} 承担；本类只从
 * {@link CanvasQueryService} 读取权威 revision，并把版本事件映射为 {@link CanvasVersionEventSource.Event}。
 *
 * <p>数据库侧由 {@code PostgresqlCanvasChangeNotifier} 在写事务内沿 {@link #CHANNEL} 发出提示，它只引用本常量，不保留第二处通道定义。
 * 本类不持有 LISTEN 连接，也不向浏览器推送事件。
 */
public final class CanvasVersionNotifications implements CanvasVersionEventSource {

  /** {@code canvas_document.revision} 前进通知的 PostgreSQL channel。 */
  public static final String CHANNEL = "canvas_revision";

  private final CanvasQueryService canvasQueryService;
  private final VersionNotificationHub<Event> hub =
      new VersionNotificationHub<>(
          CanvasVersionNotifications::parseNotification,
          revision -> new Event(revision, false),
          () -> new Event(null, true));

  public CanvasVersionNotifications(CanvasQueryService canvasQueryService) {
    this.canvasQueryService = Objects.requireNonNull(canvasQueryService, "canvasQueryService");
  }

  @Override
  public SourceSubscribed subscribe(UUID canvasId, Consumer<Event> consumer) {
    VersionNotificationHub.Subscription subscribed =
        hub.subscribe(canvasId, this::currentRevision, consumer);
    return new SourceSubscribed(subscribed.cursor(), subscribed.release()::run);
  }

  /** 由统一 PostgreSQL listener 交付一条 Canvas revision 通知。 */
  public void onNotification(String payload) {
    hub.onNotification(payload);
  }

  /** 建连/重连：覆盖断连期间不可恢复的通知。 */
  public void broadcastResync() {
    hub.broadcastResync();
  }

  private long currentRevision(UUID canvasId) {
    return canvasQueryService
        .findDocument(canvasId)
        .map(CanvasDocument::revision)
        .orElseThrow(() -> new IllegalArgumentException("unknown canvas: " + canvasId));
  }

  /** payload 为 {@code {canvasId}:{revision}}；畸形 payload 返回 {@code null} 触发 resync。 */
  static VersionNotificationHub.Notification parseNotification(String payload) {
    if (payload == null) {
      return null;
    }
    int colon = payload.indexOf(':');
    if (colon <= 0 || colon != payload.lastIndexOf(':')) {
      return null;
    }
    String rawCanvasId = payload.substring(0, colon);
    String rawRevision = payload.substring(colon + 1);
    try {
      UUID canvasId = Uuids.parseCanonical(rawCanvasId, "canvasId");
      long revision = DecimalLongs.parseNonNegative(rawRevision, "revision");
      return new VersionNotificationHub.Notification(canvasId, revision);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}

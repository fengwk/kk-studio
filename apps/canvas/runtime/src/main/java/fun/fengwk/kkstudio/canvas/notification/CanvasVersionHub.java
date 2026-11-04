package fun.fengwk.kkstudio.canvas.notification;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.platform.events.SourceSubscribed;
import fun.fengwk.kkstudio.platform.notification.SubscriptionFanOut;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * PostgreSQL {@code canvas_document.revision} 前进通知的进程内 fan-out，也是 {@code canvas_revision}
 * 通道名的唯一定义处。
 *
 * <p>{@code canvas_document} 行与 revision 是事实源；初始订阅 cursor 从 {@link CanvasQueryService}
 * 权威读取，notification payload 携带提交后的 {@code canvasId:revision}，合法 payload 只做轻量解析与 fan-out，畸形 payload
 * 广播 resync。宿主共享 LISTEN loop 启动/重连成功时也调用 {@link #broadcastResync()}，覆盖断连期间不可恢复的通知。{@link
 * #subscribe} 先注册 consumer 再读当前 revision 返回，保证返回的 cursor 之后的事件不因注册竞态丢失。
 *
 * <p>数据库侧由 {@code PostgresqlCanvasChangeNotifier} 在写事务内沿 {@link #CHANNEL}
 * 发出提示，它只引用本常量，不保留第二处通道定义；两者是同一坐标系：通道名与列名都使用 revision，不保留 version 别名。本类不持有 LISTEN 连接，也不向浏览器推送事件。
 */
@Slf4j
public final class CanvasVersionHub implements CanvasVersionEventSource {

  /** {@code canvas_document.revision} 前进通知的 PostgreSQL channel。 */
  public static final String CHANNEL = "canvas_revision";

  private final CanvasQueryService canvasQueryService;
  private final SubscriptionFanOut<Event> subscribers = new SubscriptionFanOut<>();

  public CanvasVersionHub(CanvasQueryService canvasQueryService) {
    this.canvasQueryService = Objects.requireNonNull(canvasQueryService, "canvasQueryService");
  }

  @Override
  public SourceSubscribed subscribe(UUID canvasId, Consumer<Event> consumer) {
    Objects.requireNonNull(canvasId, "canvasId");
    // 先注册 consumer 再读当前 revision 返回，保证返回的 cursor 之后的事件不因注册竞态丢失。
    Runnable unsubscribe = subscribers.subscribe(canvasId, consumer);
    try {
      return new SourceSubscribed(currentRevision(canvasId), unsubscribe::run);
    } catch (RuntimeException | Error error) {
      // 权威查询失败必须释放刚建立的注册，并保留原始异常（Error 同样不得吞）。
      unsubscribe.run();
      throw error;
    }
  }

  /** PostgreSQL LISTEN 通知入口：合法 payload 定点投递，畸形 payload 退化为全量 resync。 */
  public void onNotification(String payload) {
    CanvasRevision notification = parseNotification(payload);
    if (notification == null) {
      log.warn("malformed canvas revision notification payload={}; broadcasting resync", payload);
      broadcastResync();
      return;
    }
    subscribers.publish(notification.canvasId(), new Event(notification.revision(), false));
  }

  /** 全量 resync：LISTEN 建连/重连后的对账入口。 */
  public void broadcastResync() {
    subscribers.broadcast(new Event(null, true));
  }

  private long currentRevision(UUID canvasId) {
    return canvasQueryService
        .findDocument(canvasId)
        .map(CanvasDocument::revision)
        .orElseThrow(() -> new IllegalArgumentException("unknown canvas: " + canvasId));
  }

  static CanvasRevision parseNotification(String payload) {
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
      return new CanvasRevision(canvasId, revision);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  record CanvasRevision(UUID canvasId, long revision) {}
}

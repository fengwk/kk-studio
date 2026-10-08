package fun.fengwk.kkstudio.harness.infra.realtime;

import fun.fengwk.kkstudio.harness.infra.notification.HarnessNotifications;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeNotificationCodec.Envelope;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;

import java.util.Objects;

/**
 * 通过唯一 {@link NotificationBus} 发布 realtime live overlay 的 sink。
 *
 * <p>单条 canonical EVENT 超过逻辑消息预算时改发该 Thread 的小型 RESYNC，保持既有“超大可恢复”语义；其余情况由 carrier 分片承载， 不再受单帧 PG
 * payload 上限限制。数据库/传输异常直接向上传播，由 Runtime 既有边界隔离，不重试、不改写 durable 状态。
 */
public final class BusRealtimeEventSink implements RealtimeEventSink {

  static final String OVERSIZE_REASON = "EVENT_TOO_LARGE";

  private final NotificationBus bus;
  private final int maxMessageBytes;

  public BusRealtimeEventSink(NotificationBus bus, int maxMessageBytes) {
    if (maxMessageBytes <= 0) {
      throw new IllegalArgumentException("maxMessageBytes must be positive");
    }
    this.bus = Objects.requireNonNull(bus, "bus");
    this.maxMessageBytes = maxMessageBytes;
  }

  @Override
  public void append(RealtimeEvent event) {
    Objects.requireNonNull(event, "event");
    Envelope envelope = new Envelope.Event(event);
    if (HarnessNotifications.REALTIME.codec().encode(envelope).length > maxMessageBytes) {
      envelope = new Envelope.Resync(event.threadId(), OVERSIZE_REASON);
    }
    bus.publish(HarnessNotifications.REALTIME, NotificationAddress.broadcast(), envelope);
  }
}

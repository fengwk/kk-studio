package fun.fengwk.kkstudio.web.events;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.notification.NotificationInvalidationHub;

import java.util.UUID;
import java.util.function.Consumer;

/** Environment 连接行的状态、被接受 metadata 与租约代币变化，投递同一全局失效信号；到期由读取投影判断。 */
@Component
final class EnvironmentChangeHub implements InvalidationEventSource {

  static final String CHANNEL = "environment_changed";

  private final NotificationInvalidationHub<Event> hub =
      new NotificationInvalidationHub<>(Event::changed, Event::fullResync);

  @Override
  public AutoCloseable subscribe(UUID key, Consumer<Event> consumer) {
    return hub.subscribe(key, consumer)::run;
  }

  public void onNotification(String payload) {
    hub.onNotification(payload);
  }

  public void broadcastResync() {
    hub.broadcastResync();
  }
}

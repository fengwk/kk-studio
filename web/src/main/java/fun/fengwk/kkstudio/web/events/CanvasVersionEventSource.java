package fun.fengwk.kkstudio.web.events;

import java.util.UUID;
import java.util.function.Consumer;

/** Canvas graph version 前进通知的最小边界：订阅原子返回建立瞬间的 cursor，之后的事件保证送达。 */
interface CanvasVersionEventSource {

  record Event(Long version, boolean resync) {
    public Event {
      if (resync) {
        if (version != null) {
          throw new IllegalArgumentException("resync event must not carry a version");
        }
      } else if (version == null || version < 0L) {
        throw new IllegalArgumentException("version event must carry a non-negative graph version");
      }
    }
  }

  /**
   * 原子注册一个 Canvas 的 version 订阅：先注册 consumer 再读取当前 version 返回。返回的 cursor 之后的 version 前进保证经 {@code
   * consumer} 送达（LISTEN 断连期间由 resync 事件覆盖）；未知 Canvas 抛 {@link IllegalArgumentException} 且不遗留注册。
   */
  SourceSubscribed subscribe(UUID canvasId, Consumer<Event> consumer);
}

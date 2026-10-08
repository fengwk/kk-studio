package fun.fengwk.kkstudio.web.events;

import java.util.UUID;
import java.util.function.Consumer;

/** Canvas revision 前进通知的最小边界：订阅原子返回建立瞬间的 cursor，之后的事件保证送达。 */
interface CanvasVersionEventSource {

  record Event(Long revision, boolean resync) {
    public Event {
      if (resync) {
        if (revision != null) {
          throw new IllegalArgumentException("resync event must not carry a revision");
        }
      } else if (revision == null || revision < 0L) {
        throw new IllegalArgumentException("revision event must carry a non-negative revision");
      }
    }
  }

  /**
   * 原子注册一个 Canvas 的 revision 订阅：先注册 consumer 再读取当前 revision 返回。返回的 cursor 之后的 revision 前进保证经 {@code
   * consumer} 送达（总线建连/重连期间由 resync 事件覆盖）；未知 Canvas 抛 {@link IllegalArgumentException} 且不遗留注册。
   */
  SourceSubscribed subscribe(UUID canvasId, Consumer<Event> consumer);
}

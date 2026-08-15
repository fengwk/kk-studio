package fun.fengwk.kkstudio.web.events;

import java.util.UUID;
import java.util.function.Consumer;

/** Thread durable revision 通知的最小边界：订阅原子返回建立瞬间的 cursor，之后的事件保证送达。 */
interface ThreadRevisionEventSource {

  record Event(String revision, boolean resync) {
    public Event {
      if (resync) {
        if (revision != null) {
          throw new IllegalArgumentException("resync event must not carry a revision");
        }
      } else if (revision == null || !revision.matches("0|[1-9]\\d*")) {
        throw new IllegalArgumentException(
            "revision event must carry a canonical non-negative decimal revision");
      } else {
        try {
          Long.parseLong(revision);
        } catch (NumberFormatException error) {
          throw new IllegalArgumentException("revision event exceeds bigint range", error);
        }
      }
    }
  }

  /**
   * 原子注册一个 Thread 的 revision 订阅：先注册 consumer 再读取当前 revision 返回。返回的 cursor 之后的 revision 变化保证经 {@code
   * consumer} 送达（LISTEN 断连期间由 resync 事件覆盖）；未知 Thread 抛 {@link IllegalArgumentException} 且不遗留注册。
   */
  SourceSubscribed subscribe(UUID threadId, Consumer<Event> consumer);
}

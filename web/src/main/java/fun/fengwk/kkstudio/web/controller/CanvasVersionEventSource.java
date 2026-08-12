package fun.fengwk.kkstudio.web.controller;

import java.util.UUID;
import java.util.function.Consumer;

/** 面向 Canvas SSE 的最小 graph version 通知边界。 */
@FunctionalInterface
interface CanvasVersionEventSource {

  /** version 前进事件携带非负 version；resync 事件不携带 version。 */
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

  AutoCloseable subscribe(UUID canvasId, long afterVersion, Consumer<Event> consumer);
}

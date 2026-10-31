package fun.fengwk.kkstudio.web.environment;

import org.eclipse.jetty.websocket.api.Callback;

import java.util.function.Consumer;

final class WebSocketCallbackBridge {

  private WebSocketCallbackBridge() {}

  static Callback adapt(Runnable onSucceed, Consumer<Throwable> onFail) {
    return Callback.from(
        onSucceed != null ? onSucceed : () -> {},
        onFail != null ? onFail : t -> {});
  }

  static Callback noop() {
    return Callback.NOOP;
  }
}

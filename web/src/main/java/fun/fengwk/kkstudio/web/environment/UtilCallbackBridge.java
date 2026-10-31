package fun.fengwk.kkstudio.web.environment;

import org.eclipse.jetty.util.Callback;

import java.util.function.Consumer;

final class UtilCallbackBridge {

  private UtilCallbackBridge() {}

  static Callback from(Runnable onSucceed, Consumer<Throwable> onFail) {
    return Callback.from(
        onSucceed != null ? onSucceed : () -> {},
        onFail != null ? onFail : t -> {});
  }
}

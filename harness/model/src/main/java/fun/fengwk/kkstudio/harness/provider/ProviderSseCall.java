package fun.fengwk.kkstudio.harness.provider;

import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;

import java.net.http.HttpRequest;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 四个协议共用的阻塞 SSE 调用骨架：逐帧交给协议累积器、读完后交付 completion，并把传输与协议失败收敛为 bridge 的 terminal-once 错误。
 *
 * <p>协议差异只通过帧消费者、{@code finish} 与 {@code errorMapper} 注入。线程被中断时 transport 抛出的 CANCELLED 经 mapper
 * 映射为 {@code null}，此时不交付任何终态，由调用方根据线程中断状态收敛。
 */
public final class ProviderSseCall {

  private ProviderSseCall() {}

  public static void run(
      JdkHttpSseTransport transport,
      HttpRequest request,
      ModelCallTimeoutPolicy timeoutPolicy,
      ProviderStreamBridge bridge,
      Consumer<ServerSentEvent> onFrame,
      Supplier<ProviderCompletion> finish,
      Function<TransportException, ProviderException> errorMapper) {
    try {
      transport.execute(
          request,
          timeoutPolicy,
          HttpSseLimits.DEFAULT,
          event -> {
            try {
              onFrame.accept(event);
            } catch (ProviderException failure) {
              if (bridge.handlerFailed()) {
                throw failure;
              }
              bridge.emitError(failure);
            }
            return !bridge.isClosed();
          });
    } catch (TransportException failure) {
      ProviderException mapped = errorMapper.apply(failure);
      if (mapped != null) {
        bridge.emitError(mapped);
      }
      return;
    }
    if (bridge.isClosed()) {
      return;
    }
    try {
      bridge.emitComplete(finish.get());
    } catch (ProviderException failure) {
      if (bridge.handlerFailed()) {
        throw failure;
      }
      bridge.emitError(failure);
    }
  }
}

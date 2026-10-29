package fun.fengwk.kkstudio.harness.provider;

import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;

/**
 * 协议适配器测试用的传输替身：不发网络请求，在调用线程上按序把脚本中的 SSE 事件交给适配器（适配器返回 false 时停止），随后按脚本正常结束或抛出给定的 {@link
 * TransportException}，与真实阻塞传输器的交付契约一致。
 */
public final class ScriptedSseTransport extends JdkHttpSseTransport {

  /** execute 被覆盖，watchdog 从不调度；所有实例共享一个守护线程调度器以免泄漏线程。 */
  private static final ScheduledExecutorService UNUSED_WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "scripted-sse-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  private final List<ServerSentEvent> events;
  private final TransportException failure;
  private final List<HttpRequest> requests = new ArrayList<>();
  private int delivered;

  private ScriptedSseTransport(List<ServerSentEvent> events, TransportException failure) {
    super(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        UNUSED_WATCHDOG);
    this.events = List.copyOf(events);
    this.failure = failure;
  }

  /** 依次交付全部事件后正常结束。 */
  public static ScriptedSseTransport events(ServerSentEvent... events) {
    return new ScriptedSseTransport(List.of(events), null);
  }

  /** 依次交付事件后抛出 {@code failure}；不给事件即为发送阶段失败。 */
  public static ScriptedSseTransport failing(
      TransportException failure, ServerSentEvent... events) {
    return new ScriptedSseTransport(List.of(events), failure);
  }

  @Override
  public void execute(
      HttpRequest request,
      ModelCallTimeoutPolicy timeoutPolicy,
      HttpSseLimits limits,
      Predicate<ServerSentEvent> onEvent) {
    requests.add(request);
    for (ServerSentEvent event : events) {
      delivered++;
      if (!onEvent.test(event)) {
        return;
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** 已发出的请求（按顺序）。 */
  public List<HttpRequest> requests() {
    return requests;
  }

  /** 实际交付给适配器的事件数：适配器提前停止时小于脚本长度。 */
  public int delivered() {
    return delivered;
  }
}

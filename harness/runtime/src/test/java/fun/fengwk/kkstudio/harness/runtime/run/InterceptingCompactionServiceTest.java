package fun.fengwk.kkstudio.harness.runtime.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;
import fun.fengwk.kkstudio.harness.runtime.extension.BeforeCompactionInterceptor;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class InterceptingCompactionServiceTest {
  private static final long SESSION_ID = 11L;

  /** Interceptor 严格按输入顺序串行变换，delegate 只收到最终上下文并仅调用一次。 */
  @Test
  void appliesInterceptorsInInputOrderBeforeCallingDelegateOnce() {
    List<String> order = new ArrayList<>();
    List<BeforeCompactionInterceptor> interceptors = new ArrayList<>();
    interceptors.add(hook("first", List.of("original"), "first", order));
    interceptors.add(hook("second", List.of("original", "first"), "second", order));
    AtomicInteger delegateCalls = new AtomicInteger();
    AtomicReference<SessionContext> delegateContext = new AtomicReference<>();
    CompactionEntryPayload expected = new CompactionEntryPayload("summary", 1L, 10, "{}");
    InterceptingCompactionService service =
        new InterceptingCompactionService(
            (sessionId, context) -> {
              assertEquals(SESSION_ID, sessionId);
              delegateCalls.incrementAndGet();
              delegateContext.set(context);
              return Optional.of(expected);
            },
            interceptors);
    interceptors.clear();
    interceptors.add(context -> context.context());

    Optional<CompactionEntryPayload> result = service.compact(SESSION_ID, context("original"));

    assertEquals(List.of("first", "second"), order);
    assertEquals(List.of("original", "first", "second"), texts(delegateContext.get()));
    assertEquals(1, delegateCalls.get());
    assertSame(expected, result.orElseThrow());
  }

  /** Interceptor 返回 null 时在该步骤明确失败，delegate 不得被调用。 */
  @Test
  void rejectsNullInterceptorResultBeforeCallingDelegate() {
    AtomicInteger delegateCalls = new AtomicInteger();
    InterceptingCompactionService service =
        new InterceptingCompactionService(
            (sessionId, context) -> {
              delegateCalls.incrementAndGet();
              return Optional.empty();
            },
            List.of(context -> null));

    NullPointerException failure =
        assertThrows(
            NullPointerException.class, () -> service.compact(SESSION_ID, context("original")));

    assertEquals("before compaction interceptor result", failure.getMessage());
    assertEquals(0, delegateCalls.get());
  }

  /** Interceptor 实现异常保持原样传播，且中断后续压缩。 */
  @Test
  void propagatesInterceptorFailure() {
    IllegalStateException expected = new IllegalStateException("hook failed");
    AtomicInteger delegateCalls = new AtomicInteger();
    InterceptingCompactionService service =
        new InterceptingCompactionService(
            (sessionId, context) -> {
              delegateCalls.incrementAndGet();
              return Optional.empty();
            },
            List.of(
                context -> {
                  throw expected;
                }));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> service.compact(SESSION_ID, context("original")));

    assertSame(expected, failure);
    assertEquals(0, delegateCalls.get());
  }

  /** Delegate 的 null 与实现异常同样明确传播，并且每次调用都只执行一次。 */
  @Test
  void rejectsNullDelegateResultAndPropagatesDelegateFailure() {
    AtomicInteger nullCalls = new AtomicInteger();
    InterceptingCompactionService nullService =
        new InterceptingCompactionService(
            (sessionId, context) -> {
              nullCalls.incrementAndGet();
              return null;
            },
            List.of());

    NullPointerException nullFailure =
        assertThrows(
            NullPointerException.class, () -> nullService.compact(SESSION_ID, context("original")));

    assertEquals("compaction delegate result", nullFailure.getMessage());
    assertEquals(1, nullCalls.get());

    IllegalArgumentException expected = new IllegalArgumentException("delegate failed");
    AtomicInteger failureCalls = new AtomicInteger();
    InterceptingCompactionService failingService =
        new InterceptingCompactionService(
            (sessionId, context) -> {
              failureCalls.incrementAndGet();
              throw expected;
            },
            List.of());

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> failingService.compact(SESSION_ID, context("original")));

    assertSame(expected, failure);
    assertEquals(1, failureCalls.get());
  }

  /** 公共入口始终拒绝非正 sessionId、null context 和 null 构造依赖。 */
  @Test
  void validatesInputs() {
    CompactionService delegate = (sessionId, context) -> Optional.empty();
    InterceptingCompactionService service = new InterceptingCompactionService(delegate, List.of());

    assertThrows(IllegalArgumentException.class, () -> service.compact(0, context("original")));
    assertThrows(NullPointerException.class, () -> service.compact(SESSION_ID, null));
    assertThrows(
        NullPointerException.class, () -> new InterceptingCompactionService(null, List.of()));
    assertThrows(
        NullPointerException.class, () -> new InterceptingCompactionService(delegate, null));
  }

  private static BeforeCompactionInterceptor hook(
      String name, List<String> expectedTexts, String appendedText, List<String> order) {
    return hookContext -> {
      assertEquals(SESSION_ID, hookContext.sessionId());
      assertEquals(expectedTexts, texts(hookContext.context()));
      order.add(name);
      List<AgentMessage> messages = new ArrayList<>(hookContext.context().messages());
      messages.add(AgentMessage.system(appendedText));
      return new SessionContext(hookContext.context().config(), messages);
    };
  }

  private static SessionContext context(String... texts) {
    AgentRuntimeConfig config =
        new AgentRuntimeConfig("system", "model", "default", List.of(), List.of(), List.of(), "{}");
    return new SessionContext(config, List.of(texts).stream().map(AgentMessage::system).toList());
  }

  private static List<String> texts(SessionContext context) {
    return context.messages().stream()
        .map(message -> ((TextMessageContent) message.contents().get(0)).text())
        .toList();
  }
}

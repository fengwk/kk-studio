package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.runtime.HarnessThreadChangeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** web adapter 把 {@link ThreadRevisionEventSource} 的 cursor/handle 语义透传给 core 内部 wake 源。 */
class WebHarnessThreadChangeSourceTest {

  private static final UUID THREAD_ID = new UUID(0L, 7L);

  /** revision 与 resync 事件都折叠为纯 wake；释放幂等透传到底层 handle。 */
  @Test
  void passesCursorAndHandleThroughWithPureWakeSignals() throws Exception {
    ThreadRevisionEventSource delegate = mock(ThreadRevisionEventSource.class);
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> consumer = new AtomicReference<>();
    AtomicBoolean handleClosed = new AtomicBoolean();
    SourceSubscribed subscribed = new SourceSubscribed(42L, () -> handleClosed.set(true));
    when(delegate.subscribe(eq(THREAD_ID), any()))
        .thenAnswer(
            inv -> {
              consumer.set(inv.getArgument(1));
              return subscribed;
            });

    WebHarnessThreadChangeSource source = new WebHarnessThreadChangeSource(delegate);
    List<String> wakes = new ArrayList<>();
    HarnessThreadChangeSource.Subscription subscription =
        source.subscribe(THREAD_ID, () -> wakes.add("wake"));

    verify(delegate).subscribe(eq(THREAD_ID), any());
    assertEquals(42L, subscribed.cursor(), "subscribe cursor must pass through unchanged");

    consumer.get().accept(new ThreadRevisionEventSource.Event("5", false));
    consumer.get().accept(new ThreadRevisionEventSource.Event(null, true));
    assertEquals(2, wakes.size(), "revision and resync events must both wake the consumer");

    subscription.close();
    assertTrue(handleClosed.get(), "close must release the underlying hub handle");
    subscription.close();
    assertEquals(2, wakes.size(), "close must not fire wakes and must be idempotent");
    // 幂等：第二次 close 不再触碰底层 handle。
    assertTrue(handleClosed.get());
  }

  /** 委托抛未知 Thread 异常时原样传播，且不产生可释放的句柄。 */
  @Test
  void propagatesUnknownThreadAndRejectsNulls() {
    ThreadRevisionEventSource delegate = mock(ThreadRevisionEventSource.class);
    when(delegate.subscribe(eq(THREAD_ID), any()))
        .thenThrow(new IllegalArgumentException("unknown thread"));
    WebHarnessThreadChangeSource source = new WebHarnessThreadChangeSource(delegate);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> source.subscribe(THREAD_ID, () -> {}));
    assertEquals("unknown thread", error.getMessage());
    assertThrows(NullPointerException.class, () -> source.subscribe(null, () -> {}));
    assertThrows(NullPointerException.class, () -> source.subscribe(THREAD_ID, null));
  }
}

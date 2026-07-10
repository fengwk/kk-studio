package fun.fengwk.kkstudio.core.testing;

import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.AssistantResponse;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandle;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandler;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * @author fengwk
 */
public class StubProviderManager implements ProviderManager {

  private final Queue<Consumer<AssistantResponseHandler>> scripts = new ConcurrentLinkedQueue<>();
  private final Provider provider = new StubProvider();
  private final AtomicReference<AssistantResponseHandle> nextHandle =
      new AtomicReference<>(noopHandle());
  private volatile RuntimeException providerResolutionFailure;

  public void reset() {
    scripts.clear();
    nextHandle.set(noopHandle());
    providerResolutionFailure = null;
  }

  public void failProviderResolution(String message) {
    providerResolutionFailure = new IllegalStateException(message);
  }

  public void enqueueText(String text) {
    scripts.offer(
        handler ->
            handler.onComplete(
                AssistantResponse.builder().text(text).metadata(new AssistantMetadata()).build(),
                noopHandle()));
  }

  public void enqueueAsyncText(String text) {
    scripts.offer(
        handler ->
            new Thread(
                    () ->
                        handler.onComplete(
                            AssistantResponse.builder()
                                .text(text)
                                .metadata(new AssistantMetadata())
                                .build(),
                            noopHandle()),
                    "stub-provider-async")
                .start());
  }

  /**
   * 入队一个会在发出首个 delta 后阻塞在 releaseLatch 上的 stub。 调用方在 poll 到事件增长后调 release() 释放，让 run 继续到
   * assistant_end。
   */
  public PausableScript enqueuePausableText(String text) {
    PausableScript script = new PausableScript(text);
    scripts.offer(
        handler -> {
          nextHandle.set(script.handle);
          new Thread(
                  () -> {
                    handler.onTextDelta("", script.handle);
                    try {
                      script.releaseLatch.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                    handler.onComplete(
                        AssistantResponse.builder()
                            .text(text)
                            .metadata(new AssistantMetadata())
                            .build(),
                        script.handle);
                    script.completionLatch.countDown();
                  },
                  "stub-provider-pausable")
              .start();
        });
    return script;
  }

  public static final class PausableScript {
    private final CountDownLatch releaseLatch = new CountDownLatch(1);
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private final CancellableHandle handle = new CancellableHandle();

    PausableScript(String text) {}

    public void release() {
      releaseLatch.countDown();
    }

    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
      return completionLatch.await(timeout, unit);
    }

    public boolean isCancelled() {
      return handle.isCancelled();
    }
  }

  public void enqueueError(String message) {
    scripts.offer(handler -> handler.onError(new RuntimeException(message), noopHandle()));
  }

  @Override
  public Provider getProvider(ProviderInfo providerInfo) {
    RuntimeException failure = providerResolutionFailure;
    if (failure != null) {
      throw failure;
    }
    return provider;
  }

  private Consumer<AssistantResponseHandler> nextScript() {
    Consumer<AssistantResponseHandler> script = scripts.poll();
    if (script != null) {
      return script;
    }
    return handler ->
        handler.onComplete(
            AssistantResponse.builder()
                .text("stub response")
                .metadata(new AssistantMetadata())
                .build(),
            noopHandle());
  }

  private static AssistantResponseHandle noopHandle() {
    return new AssistantResponseHandle() {
      @Override
      public void cancel() {}

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
  }

  private static final class CancellableHandle implements AssistantResponseHandle {
    private volatile boolean cancelled;

    @Override
    public void cancel() {
      cancelled = true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }
  }

  private final class StubProvider implements Provider {

    @Override
    public ProviderType getProviderType() {
      return ProviderType.openai;
    }

    @Override
    public AssistantResponseHandle asyncChat(
        List<AgentMessage> messages,
        ModelInfo modelInfo,
        Variant variant,
        List<ToolInfo> toolInfos,
        AssistantResponseHandler handler) {
      nextScript().accept(handler);
      return nextHandle.getAndSet(noopHandle());
    }
  }
}

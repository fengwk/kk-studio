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
import java.util.function.Consumer;

/**
 * @author fengwk
 */
public class StubProviderManager implements ProviderManager {

  private final Queue<Consumer<AssistantResponseHandler>> scripts = new ConcurrentLinkedQueue<>();
  private final Provider provider = new StubProvider();

  public void reset() {
    scripts.clear();
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
        handler ->
            new Thread(
                    () -> {
                      handler.onTextDelta("", noopHandle());
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
                          noopHandle());
                    },
                    "stub-provider-pausable")
                .start());
    return script;
  }

  public static final class PausableScript {
    private final CountDownLatch releaseLatch = new CountDownLatch(1);

    PausableScript(String text) {}

    public void release() {
      releaseLatch.countDown();
    }
  }

  public void enqueueError(String message) {
    scripts.offer(handler -> handler.onError(new RuntimeException(message), noopHandle()));
  }

  @Override
  public Provider getProvider(ProviderInfo providerInfo) {
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
      return noopHandle();
    }
  }
}

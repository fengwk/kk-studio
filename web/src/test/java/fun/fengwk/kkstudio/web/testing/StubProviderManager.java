package fun.fengwk.kkstudio.web.testing;

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
import java.util.function.Consumer;

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

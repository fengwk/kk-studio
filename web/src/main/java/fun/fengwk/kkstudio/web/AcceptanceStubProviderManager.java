package fun.fengwk.kkstudio.web;

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

/**
 * 本地 H2 验收模式下的最小 stub provider。
 *
 * <p>当前默认返回固定文本，避免本地验收依赖真实模型 provider。
 *
 * @author fengwk
 */
public class AcceptanceStubProviderManager implements ProviderManager {

  private static final String DEFAULT_RESPONSE = "stub response";

  private final Provider provider = new AcceptanceStubProvider();

  @Override
  public Provider getProvider(ProviderInfo providerInfo) {
    return provider;
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

  private static final class AcceptanceStubProvider implements Provider {

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
      handler.onComplete(
          AssistantResponse.builder()
              .text(DEFAULT_RESPONSE)
              .metadata(new AssistantMetadata())
              .build(),
          noopHandle());
      return noopHandle();
    }
  }
}

package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.AssistantResponse;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandle;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandler;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.AssistantResponse;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandle;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandler;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * @author fengwk
 */
public class AcceptanceStubProviderManagerTest {

  @Test
  public void shouldCompleteWithFixedStubResponse() {
    AcceptanceStubProviderManager providerManager = new AcceptanceStubProviderManager();
    Provider provider = providerManager.getProvider(null);
    AtomicReference<AssistantResponse> responseRef = new AtomicReference<>();

    AssistantResponseHandle handle =
        provider.asyncChat(
            List.of(),
            ModelInfo.builder().provider("stub").name("acceptance-stub").build(),
            Variant.builder().name("acceptance").build(),
            List.of(),
            new AssistantResponseHandler() {
              @Override
              public void onTextDelta(String textDelta, AssistantResponseHandle responseHandle) {}

              @Override
              public void onThinkingDelta(
                  String thinkingDelta, AssistantResponseHandle responseHandle) {}

              @Override
              public void onComplete(
                  AssistantResponse response, AssistantResponseHandle responseHandle) {
                responseRef.set(response);
                assertFalse(responseHandle.isCancelled());
                responseHandle.cancel();
              }

              @Override
              public void onToolCallDelta(
                  IndexedToolCallDelta toolCallDelta, AssistantResponseHandle responseHandle) {}

              @Override
              public void onToolCallComplete(
                  Integer index, ToolCall toolCall, AssistantResponseHandle responseHandle) {}

              @Override
              public void onError(Throwable error, AssistantResponseHandle responseHandle) {
                fail(error);
              }
            });

    assertEquals(ProviderType.openai, provider.getProviderType());
    assertFalse(handle.isCancelled());
    AssistantResponse response = responseRef.get();
    assertNotNull(response);
    assertEquals("stub response", response.getText());
    assertNotNull(response.getMetadata());
  }
}

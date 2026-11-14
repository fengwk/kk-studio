package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * 供 Golden 守护测试驱动 Chat 编码器与流式累积器的适配入口。
 *
 * <p>仅存在于测试源集：编码器与累积器都是包内可见的协作者，跨包测试需要这一层最薄的转发。
 */
public final class OpenAiChatGoldenHarness {

  private final OpenAiChatStreamAccumulator accumulator;
  private final List<ProviderStreamEvent> events = new ArrayList<>();

  public OpenAiChatGoldenHarness(ProviderRequest request, ProviderDescriptor descriptor) {
    this.accumulator =
        new OpenAiChatStreamAccumulator(
            request,
            descriptor,
            new ProviderStreamBridge(
                new ProviderStreamHandler() {
                  @Override
                  public void onEvent(ProviderStreamEvent event) {
                    events.add(event);
                  }

                  @Override
                  public void onComplete(ProviderCompletion completion) {}

                  @Override
                  public void onError(ProviderException error) {}
                }));
  }

  /** 与正式发送路径完全相同的请求编码。 */
  public static byte[] encode(ProviderRequest request, ProviderDescriptor descriptor) {
    return new OpenAiChatRequestEncoder().encode(request, descriptor).bodyUtf8Bytes();
  }

  public void handleData(String data) {
    accumulator.handleData(data);
  }

  public ProviderCompletion finish() {
    return accumulator.finish();
  }

  public List<ProviderStreamEvent> events() {
    return List.copyOf(events);
  }
}

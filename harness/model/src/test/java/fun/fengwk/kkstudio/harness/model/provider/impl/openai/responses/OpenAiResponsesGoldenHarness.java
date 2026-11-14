package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * 供 Golden 守护测试驱动 Responses 编码器与流式累积器的适配入口。
 *
 * <p>仅存在于测试源集：编码器的协作类型是包内可见的，跨包测试需要这一层最薄的转发。
 */
public final class OpenAiResponsesGoldenHarness {

  private final OpenAiResponsesStreamAccumulator accumulator;
  private final List<ProviderProtocolEvent> protocolEvents = new ArrayList<>();
  private final List<ProviderStreamEvent> events = new ArrayList<>();

  public OpenAiResponsesGoldenHarness(ProviderRequest request, ProviderDescriptor descriptor) {
    this.accumulator =
        new OpenAiResponsesStreamAccumulator(
            request,
            descriptor,
            new ProviderStreamBridge(
                new ProviderStreamHandler() {
                  @Override
                  public void onEvent(ProviderStreamEvent event) {
                    events.add(event);
                  }

                  @Override
                  public void onProtocolEvent(ProviderProtocolEvent event) {
                    protocolEvents.add(event);
                  }

                  @Override
                  public void onComplete(ProviderCompletion completion) {}

                  @Override
                  public void onError(ProviderException error) {}
                }));
  }

  /** 与正式发送路径完全相同的请求编码。 */
  public static byte[] encode(ProviderRequest request, ProviderDescriptor descriptor) {
    return new OpenAiResponsesRequestEncoder().encode(request, descriptor).bodyUtf8Bytes();
  }

  public void handleEvent(String eventName, String data) {
    accumulator.handleEvent(eventName, data);
  }

  public void processEvent(JsonNode node) {
    accumulator.processEvent(node);
  }

  public ProviderCompletion finish() {
    return accumulator.finish();
  }

  public List<ProviderProtocolEvent> protocolEvents() {
    return List.copyOf(protocolEvents);
  }

  public List<ProviderStreamEvent> events() {
    return List.copyOf(events);
  }
}

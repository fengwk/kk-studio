package fun.fengwk.kkstudio.harness.provider.gemini;

import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.provider.ProviderSseCall;
import fun.fengwk.kkstudio.harness.provider.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Objects;

/** Google AI Gemini GenerateContent 流式模型提供商实现。 */
final class GeminiModelProvider implements ModelProvider {

  private static final String API_KEY_HEADER = "x-goog-api-key";

  /** 无 SSE name 帧的稳定默认事件名。 */
  private static final String DEFAULT_PROTOCOL_EVENT_TYPE = "gemini.generateContent.response";

  /** 无 SSE name 的流结束帧的事件名。 */
  private static final String DONE_EVENT_TYPE = "done";

  private static final String DONE_DATA = "[DONE]";

  private final JdkHttpSseTransport transport;
  private final ProviderDescriptor descriptor;
  private final String apiKey;
  private final GeminiRequestEncoder encoder;

  GeminiModelProvider(JdkHttpSseTransport transport, ProviderDescriptor descriptor, String apiKey) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.apiKey = apiKey;
    this.encoder = new GeminiRequestEncoder();
  }

  public ProviderDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public void stream(ProviderRequest request, ProviderStreamHandler handler) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(handler, "handler");

    ProviderStreamBridge bridge = new ProviderStreamBridge(handler);

    GeminiEncodedRequest encoded;
    try {
      encoded = encoder.encode(request, descriptor);
    } catch (ProviderException exception) {
      bridge.emitError(exception);
      return;
    }

    URI streamUri;
    try {
      streamUri =
          GeminiEndpoints.resolveStreamUri(descriptor.endpoint(), request.model().modelId());
    } catch (IllegalArgumentException ex) {
      bridge.emitError(
          new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid model name for Gemini endpoint"));
      return;
    }

    HttpRequest httpRequest;
    try {
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder(streamUri)
              .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.bodyUtf8Bytes()))
              .header("Content-Type", "application/json")
              .header("Accept", "text/event-stream");
      if (apiKey != null && !apiKey.isBlank()) {
        requestBuilder.header(API_KEY_HEADER, apiKey);
      }
      httpRequest = requestBuilder.build();
    } catch (IllegalArgumentException ex) {
      bridge.emitError(
          new ProviderException(ProviderErrorKind.INVALID_REQUEST, "invalid request header value"));
      return;
    }

    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    ProviderSseCall.run(
        transport,
        httpRequest,
        descriptor.modelCallTimeoutPolicy(),
        bridge,
        event -> {
          // 每条 transport SSE frame 先原样交付原生通道，再进入规范化累积器：两条通道独立且互补
          bridge.emitProtocolEvent(
              new ProviderProtocolEvent(resolveProtocolEventType(event), event.data()));
          accumulator.handleEvent(event.event(), event.data());
        },
        accumulator::finish,
        GeminiErrorMapper::mapTransportException);
  }

  @Override
  public String toString() {
    return "GeminiModelProvider[]";
  }

  /**
   * 原生事件名：优先 SSE {@code event} name；无 name 的 {@code [DONE]} 稳定记为 {@code done}，其余无 name 帧统一使用
   * 稳定的默认事件名。
   */
  private static String resolveProtocolEventType(ServerSentEvent event) {
    String name = event.event();
    if (name != null && !name.isBlank()) {
      return name;
    }
    if (DONE_DATA.equals(event.data().trim())) {
      return DONE_EVENT_TYPE;
    }
    return DEFAULT_PROTOCOL_EVENT_TYPE;
  }
}

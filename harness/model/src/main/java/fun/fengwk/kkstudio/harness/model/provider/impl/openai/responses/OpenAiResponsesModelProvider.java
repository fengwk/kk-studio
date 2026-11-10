package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import fun.fengwk.kkstudio.harness.model.limits.RequestLimitsPresets;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.PreparedProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderSseCall;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.model.provider.impl.transport.JdkHttpSseTransport;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Objects;
import java.util.Optional;

/** OpenAI Responses 消息流式模型提供商实现。 */
final class OpenAiResponsesModelProvider implements ModelProvider {

  private static final String AUTHORIZATION_HEADER = "Authorization";

  private final JdkHttpSseTransport transport;
  private final ProviderDescriptor descriptor;
  private final String apiKey;
  private final URI responsesUri;
  private final OpenAiResponsesRequestEncoder encoder;

  OpenAiResponsesModelProvider(
      JdkHttpSseTransport transport,
      ProviderDescriptor descriptor,
      String apiKey,
      URI responsesUri) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.apiKey = apiKey;
    this.responsesUri = Objects.requireNonNull(responsesUri, "responsesUri");
    this.encoder = new OpenAiResponsesRequestEncoder();
  }

  public ProviderDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public void stream(ProviderRequest request, ProviderStreamHandler handler) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(handler, "handler");

    ProviderStreamBridge bridge = new ProviderStreamBridge(handler);

    OpenAiResponsesEncodedRequest encoded;
    try {
      encoded = encoder.encode(request, descriptor);
    } catch (ProviderException exception) {
      bridge.emitError(exception);
      return;
    }
    // 先计量最终 body 并按端点已知限制检查；超限不发送。计量、定价观察与 HTTP 发送共用同一份字节。
    PreparedProviderRequest prepared =
        PreparedProviderRequest.prepare(
            request,
            encoded.bodyUtf8Bytes(),
            RequestLimitsPresets.resolve(descriptor.type(), descriptor.endpoint()));
    Optional<ProviderException> violation = prepared.limitViolation();
    if (violation.isPresent()) {
      bridge.emitError(violation.get());
      return;
    }
    byte[] body = prepared.body();
    handler.onRequestBody(body);

    HttpRequest httpRequest;
    try {
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder(responsesUri)
              .POST(HttpRequest.BodyPublishers.ofByteArray(body))
              .header("Content-Type", "application/json")
              .header("Accept", "text/event-stream");
      if (apiKey != null && !apiKey.isBlank()) {
        requestBuilder.header(AUTHORIZATION_HEADER, "Bearer " + apiKey);
      }
      httpRequest = requestBuilder.build();
    } catch (IllegalArgumentException ex) {
      bridge.emitError(
          new ProviderException(ProviderErrorKind.INVALID_REQUEST, "invalid request header value"));
      return;
    }

    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(request, descriptor, bridge);

    ProviderSseCall.run(
        transport,
        httpRequest,
        descriptor.modelCallTimeoutPolicy(),
        bridge,
        event -> accumulator.handleEvent(event.event(), event.data()),
        accumulator::finish,
        OpenAiResponsesErrorMapper::mapTransportException);
  }
}

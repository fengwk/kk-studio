package fun.fengwk.kkstudio.harness.provider.openai.responses;

import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.provider.ProviderSseCall;
import fun.fengwk.kkstudio.harness.provider.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Objects;

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

    HttpRequest httpRequest;
    try {
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder(responsesUri)
              .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.bodyUtf8Bytes()))
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

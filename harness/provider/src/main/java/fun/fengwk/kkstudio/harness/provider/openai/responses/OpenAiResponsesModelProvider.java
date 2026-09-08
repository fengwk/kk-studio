package fun.fengwk.kkstudio.harness.provider.openai.responses;

import fun.fengwk.kkstudio.harness.provider.transport.HttpOpenMetadata;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseCallback;
import fun.fengwk.kkstudio.harness.provider.transport.HttpSseLimits;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.provider.transport.ServerSentEvent;
import fun.fengwk.kkstudio.harness.provider.transport.TransportException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;

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
  private final OpenAiResponsesConfig config;
  private final OpenAiResponsesRequestEncoder encoder;

  OpenAiResponsesModelProvider(
      JdkHttpSseTransport transport,
      ProviderDescriptor descriptor,
      String apiKey,
      URI responsesUri,
      OpenAiResponsesConfig config) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.apiKey = apiKey;
    this.responsesUri = Objects.requireNonNull(responsesUri, "responsesUri");
    this.config = Objects.requireNonNull(config, "config");
    this.encoder = new OpenAiResponsesRequestEncoder();
  }

  public ProviderDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(handler, "handler");

    OpenAiResponsesStreamBridge bridge = new OpenAiResponsesStreamBridge(handler);

    OpenAiResponsesEncodedRequest encoded;
    try {
      encoded = encoder.encode(request, descriptor, config);
    } catch (ProviderException exception) {
      bridge.emitError(exception);
      return bridge;
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
      return bridge;
    }

    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            request, descriptor, encoded.sourcePrefixHash(), bridge);

    HttpSseCallback callback =
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {
            // 连接已建立
          }

          @Override
          public void onEvent(ServerSentEvent event) {
            try {
              accumulator.handleEvent(event.event(), event.data());
            } catch (ProviderException pe) {
              bridge.emitError(pe);
            }
          }

          @Override
          public void onComplete() {
            try {
              ProviderCompletion completion = accumulator.finish();
              bridge.emitComplete(completion);
            } catch (ProviderException pe) {
              bridge.emitError(pe);
            }
          }

          @Override
          public void onFailure(TransportException error) {
            ProviderException mapped = OpenAiResponsesErrorMapper.mapTransportException(error);
            if (mapped != null) {
              bridge.emitError(mapped);
            }
          }
        };

    ProviderStream transportStream;
    try {
      transportStream =
          transport.stream(
              httpRequest, descriptor.modelCallTimeoutPolicy(), HttpSseLimits.DEFAULT, callback);
    } catch (TransportException te) {
      ProviderException pe = OpenAiResponsesErrorMapper.mapTransportException(te);
      if (pe != null) {
        bridge.emitError(pe);
      }
      return bridge;
    } catch (RuntimeException ex) {
      throw new RuntimeException("transport execution failed");
    }

    bridge.bind(transportStream);
    return bridge;
  }
}

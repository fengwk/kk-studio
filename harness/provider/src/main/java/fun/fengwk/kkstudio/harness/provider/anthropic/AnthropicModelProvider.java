package fun.fengwk.kkstudio.harness.provider.anthropic;

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

/** Anthropic 消息流式模型提供商实现。 */
final class AnthropicModelProvider implements ModelProvider {

  private static final String ANTHROPIC_VERSION_HEADER = "anthropic-version";
  private static final String ANTHROPIC_VERSION_VALUE = "2023-06-01";
  private static final String API_KEY_HEADER = "x-api-key";
  private static final String AUTHORIZATION_HEADER = "Authorization";

  private final JdkHttpSseTransport transport;
  private final ProviderDescriptor descriptor;
  private final String apiKey;
  private final URI messagesUri;
  private final AnthropicRequestEncoder encoder;

  AnthropicModelProvider(
      JdkHttpSseTransport transport,
      ProviderDescriptor descriptor,
      String apiKey,
      URI messagesUri) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.apiKey = apiKey;
    this.messagesUri = Objects.requireNonNull(messagesUri, "messagesUri");
    this.encoder = new AnthropicRequestEncoder();
  }

  public ProviderDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(handler, "handler");

    AnthropicStreamBridge bridge = new AnthropicStreamBridge(handler);

    AnthropicEncodedRequest encoded;
    try {
      encoded = encoder.encode(request, descriptor);
    } catch (ProviderException exception) {
      bridge.emitError(exception);
      return bridge;
    }

    HttpRequest httpRequest;
    try {
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder(messagesUri)
              .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.bodyUtf8Bytes()))
              .header("Content-Type", "application/json")
              .header("Accept", "text/event-stream")
              .header(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION_VALUE);
      if (apiKey != null && !apiKey.isBlank()) {
        requestBuilder.header(API_KEY_HEADER, apiKey);
        requestBuilder.header(AUTHORIZATION_HEADER, "Bearer " + apiKey);
      }
      httpRequest = requestBuilder.build();
    } catch (IllegalArgumentException ex) {
      bridge.emitError(
          new ProviderException(ProviderErrorKind.INVALID_REQUEST, "invalid request header value"));
      return bridge;
    }

    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, encoded.sourcePrefixHash(), bridge);

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
            ProviderException mapped = AnthropicErrorMapper.mapTransportException(error);
            if (mapped != null) {
              bridge.emitError(mapped);
            }
          }
        };

    try {
      ProviderStream stream =
          transport.stream(
              httpRequest, descriptor.modelCallTimeoutPolicy(), HttpSseLimits.DEFAULT, callback);
      bridge.bind(stream);
    } catch (RuntimeException ex) {
      throw new RuntimeException("transport execution failed");
    }

    return bridge;
  }

  @Override
  public String toString() {
    return "AnthropicModelProvider[]";
  }
}

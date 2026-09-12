package fun.fengwk.kkstudio.harness.provider.gemini;

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

/** Google AI Gemini GenerateContent 流式模型提供商实现。 */
final class GeminiModelProvider implements ModelProvider {

  private static final String API_KEY_HEADER = "x-goog-api-key";

  private final JdkHttpSseTransport transport;
  private final ProviderDescriptor descriptor;
  private final String apiKey;
  private final URI baseUri;
  private final GeminiRequestEncoder encoder;

  GeminiModelProvider(
      JdkHttpSseTransport transport, ProviderDescriptor descriptor, String apiKey, URI baseUri) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.apiKey = apiKey;
    this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
    this.encoder = new GeminiRequestEncoder();
  }

  public ProviderDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(handler, "handler");

    GeminiStreamBridge bridge = new GeminiStreamBridge(handler);

    GeminiEncodedRequest encoded;
    try {
      encoded = encoder.encode(request, descriptor);
    } catch (ProviderException exception) {
      bridge.emitError(exception);
      return bridge;
    }

    URI streamUri;
    try {
      streamUri =
          GeminiEndpoints.resolveStreamUri(descriptor.endpoint(), request.model().modelId());
    } catch (IllegalArgumentException ex) {
      bridge.emitError(
          new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "invalid model name for Gemini endpoint"));
      return bridge;
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
      return bridge;
    }

    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(request, descriptor, encoded.sourcePrefixHash(), bridge);

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
            ProviderException mapped = GeminiErrorMapper.mapTransportException(error);
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
    return "GeminiModelProvider[]";
  }
}

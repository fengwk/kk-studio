package fun.fengwk.kkstudio.harness.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderProtocolEvent;
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
  private static final String ANTHROPIC_BETA_HEADER = "anthropic-beta";
  private static final String INTERLEAVED_THINKING_BETA_VALUE = "interleaved-thinking-2025-05-14";
  private static final String API_KEY_HEADER = "x-api-key";
  private static final String AUTHORIZATION_HEADER = "Authorization";

  /** SSE 既无 event name 也无 JSON {@code type} 时使用的稳定事件名。 */
  private static final String DEFAULT_PROTOCOL_EVENT_TYPE = "anthropic.message.event";

  private static final String DONE_DATA = "[DONE]";
  private static final String DONE_PROTOCOL_EVENT_TYPE = "done";

  /** 仅用于从原生 payload 推导事件名；payload 本体不落到日志、normalized event 或 ProviderResponse。 */
  private static final ObjectMapper PROTOCOL_EVENT_MAPPER = new ObjectMapper();

  private final JdkHttpSseTransport transport;
  private final ProviderDescriptor descriptor;
  private final String apiKey;
  private final URI messagesUri;
  private final AnthropicConfiguration configuration;
  private final AnthropicRequestEncoder encoder;

  AnthropicModelProvider(
      JdkHttpSseTransport transport,
      ProviderDescriptor descriptor,
      String apiKey,
      URI messagesUri) {
    this(transport, descriptor, apiKey, messagesUri, AnthropicConfiguration.defaults());
  }

  AnthropicModelProvider(
      JdkHttpSseTransport transport,
      ProviderDescriptor descriptor,
      String apiKey,
      URI messagesUri,
      AnthropicConfiguration configuration) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.apiKey = apiKey;
    this.messagesUri = Objects.requireNonNull(messagesUri, "messagesUri");
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.encoder = new AnthropicRequestEncoder(this.configuration);
  }

  public ProviderDescriptor descriptor() {
    return descriptor;
  }

  /** 把一条 transport SSE 帧还原为原生协议事件；payload 原样保留（含 blank 与 {@code [DONE]}）。 */
  static ProviderProtocolEvent toProtocolEvent(ServerSentEvent event) {
    Objects.requireNonNull(event, "event");
    String data = event.data();
    return new ProviderProtocolEvent(protocolEventType(event.event(), data), data);
  }

  /** 事件名优先级：非空 SSE event name → {@code [DONE]} 哨兵 → JSON {@code type} → 稳定默认名。 */
  private static String protocolEventType(String eventName, String data) {
    if (eventName != null && !eventName.isBlank()) {
      return eventName;
    }
    if (data != null && DONE_DATA.equals(data.trim())) {
      return DONE_PROTOCOL_EVENT_TYPE;
    }
    String jsonType = jsonTypeOf(data);
    return jsonType != null ? jsonType : DEFAULT_PROTOCOL_EVENT_TYPE;
  }

  private static String jsonTypeOf(String data) {
    if (data == null || data.isBlank()) {
      return null;
    }
    JsonNode payload;
    try {
      payload = PROTOCOL_EVENT_MAPPER.readTree(data);
    } catch (JsonProcessingException exception) {
      return null;
    }
    JsonNode typeNode = payload == null ? null : payload.path("type");
    if (typeNode == null || !typeNode.isTextual() || typeNode.textValue().isBlank()) {
      return null;
    }
    return typeNode.textValue();
  }

  AnthropicConfiguration configuration() {
    return configuration;
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
      if (encoded.requiresInterleavedThinkingBeta()) {
        requestBuilder.header(ANTHROPIC_BETA_HEADER, INTERLEAVED_THINKING_BETA_VALUE);
      }
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
            // 每条 transport SSE 帧先 exactly-once 原生回调，再进入 normalized/error 语义处理
            bridge.emitProtocolEvent(toProtocolEvent(event));
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

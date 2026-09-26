package fun.fengwk.kkstudio.harness.provider.openai.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

/** OpenAI Chat Completions 流式模型提供商实现。 */
final class OpenAiChatModelProvider implements ModelProvider {

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String CHUNK_EVENT_TYPE = "chat.completion.chunk";
  private static final String DONE_EVENT_TYPE = "done";
  private static final String DONE_DATA = "[DONE]";

  private static final ObjectMapper PROTOCOL_EVENT_MAPPER = new ObjectMapper();

  static {
    PROTOCOL_EVENT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    PROTOCOL_EVENT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private final JdkHttpSseTransport transport;
  private final ProviderDescriptor descriptor;
  private final String apiKey;
  private final URI chatUri;
  private final OpenAiChatConfiguration config;
  private final OpenAiChatRequestEncoder encoder;

  OpenAiChatModelProvider(
      JdkHttpSseTransport transport,
      ProviderDescriptor descriptor,
      String apiKey,
      URI chatUri,
      OpenAiChatConfiguration config) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.apiKey = apiKey;
    this.chatUri = Objects.requireNonNull(chatUri, "chatUri");
    this.config = Objects.requireNonNull(config, "config");
    this.encoder = new OpenAiChatRequestEncoder();
  }

  public ProviderDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(handler, "handler");

    OpenAiChatStreamBridge bridge = new OpenAiChatStreamBridge(handler);

    OpenAiChatEncodedRequest encoded;
    try {
      encoded = encoder.encode(request, descriptor, config);
    } catch (ProviderException exception) {
      bridge.emitError(exception);
      return bridge;
    }

    HttpRequest httpRequest;
    try {
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder(chatUri)
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

    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            request, descriptor, config, encoded.sourcePrefixHash(), bridge);

    HttpSseCallback callback =
        new HttpSseCallback() {
          @Override
          public void onOpen(HttpOpenMetadata metadata) {
            // 连接已建立
          }

          @Override
          public void onEvent(ServerSentEvent event) {
            // 每条 transport 帧先原样回调一次 native 事件，再交给规范化累积器；error 帧因此也是「先 raw 后 error」
            bridge.emitProtocolEvent(
                new ProviderProtocolEvent(protocolEventType(event), event.data()));
            try {
              accumulator.handleData(event.data());
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
            ProviderException mapped = OpenAiChatErrorMapper.mapTransportException(error);
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
    return "OpenAiChatModelProvider[]";
  }

  /**
   * 推导 native 事件名：优先 transport 给出的 SSE event 名，其次 payload 的 {@code object} / {@code type}
   * 判定字段，最后回落到 {@code chat.completion.chunk}；终止帧 {@code [DONE]} 固定为 {@code
   * done}。事件名只描述帧的协议种类，payload 原样透传。
   */
  private static String protocolEventType(ServerSentEvent event) {
    String eventName = event.event();
    if (eventName != null && !eventName.isBlank()) {
      return eventName;
    }
    String data = event.data();
    if (DONE_DATA.equals(data.trim())) {
      return DONE_EVENT_TYPE;
    }
    JsonNode payload = tryParseObject(data);
    if (payload != null) {
      JsonNode objectField = payload.get("object");
      if (objectField != null && objectField.isTextual() && !objectField.textValue().isBlank()) {
        return objectField.textValue();
      }
      JsonNode typeField = payload.get("type");
      if (typeField != null && typeField.isTextual() && !typeField.textValue().isBlank()) {
        return typeField.textValue();
      }
    }
    return CHUNK_EVENT_TYPE;
  }

  private static JsonNode tryParseObject(String data) {
    if (data == null || data.isBlank()) {
      return null;
    }
    try {
      JsonNode node = PROTOCOL_EVENT_MAPPER.readTree(data);
      return node != null && node.isObject() ? node : null;
    } catch (JsonProcessingException exception) {
      // 非法 payload 由规范化累积器以 INVALID_RESPONSE 拒绝，事件名回落到 chunk
      return null;
    }
  }
}

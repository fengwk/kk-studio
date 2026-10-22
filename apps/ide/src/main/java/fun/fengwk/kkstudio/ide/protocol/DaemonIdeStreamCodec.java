package fun.fengwk.kkstudio.ide.protocol;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 反向数据面 wire 协议的严格 codec：文本控制帧（{@code HTTP_RESPONSE}/{@code WS_READY}/{@code ERROR}）与二进制帧
 * （byte1..byte5）。
 *
 * <p>这是「一个 HTTP exchange / 一个 IDE WebSocket 一条反向数据连接」的纯 wire 层：不持有会话状态、不做背压裁决（那是 {@link
 * IdeStreamCreditWindow} 的职责），只做形态校验与字节编解码。任何未知控制帧类型、非法判别字节、越界长度或超窗 ACK 都 fail-closed。
 */
public final class DaemonIdeStreamCodec {

  /** 单个二进制帧载荷上限（帧头不计）。 */
  public static final int MAX_PAYLOAD_BYTES = 65536;

  /** 单条文本控制帧 UTF-8 字节上限（与 Node peer 一致）。 */
  public static final int MAX_TEXT_FRAME_BYTES = 16 * 1024;

  private static final byte FLAG_HTTP_BODY = 1;
  private static final byte FLAG_HTTP_END = 2;
  private static final byte FLAG_ACK = 3;
  private static final byte FLAG_WS_TEXT = 4;
  private static final byte FLAG_WS_BINARY = 5;

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> HTTP_RESPONSE_FIELDS = Set.of("type", "status", "headers");
  private static final Set<String> WS_READY_FIELDS = Set.of("type");
  private static final Set<String> ERROR_FIELDS = Set.of("type", "message");

  public String encodeHttpResponse(IdeStreamControlFrame.HttpResponse response) {
    Objects.requireNonNull(response, "response");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("type", "HTTP_RESPONSE");
    root.put("status", response.status());
    ArrayNode headers = root.putArray("headers");
    for (IdeHeader header : response.headers()) {
      ArrayNode pair = headers.addArray();
      pair.add(header.name());
      pair.add(header.value());
    }
    return write(root, "http response");
  }

  public String encodeWsReady() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("type", "WS_READY");
    return write(root, "ws ready");
  }

  public String encodeError(String message) {
    IdeStreamControlFrame.Error frame = new IdeStreamControlFrame.Error(message);
    ObjectNode root = MAPPER.createObjectNode();
    root.put("type", "ERROR");
    root.put("message", frame.message());
    return write(root, "error");
  }

  public IdeStreamControlFrame decodeControl(String json) {
    Objects.requireNonNull(json, "json");
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_FRAME_BYTES) {
      throw new DaemonProtocolException("stream control frame exceeds control message limit");
    }
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("malformed stream control frame", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("stream control frame must be an object");
    }
    JsonNode typeNode = root.get("type");
    if (typeNode == null || !typeNode.isString()) {
      throw new DaemonProtocolException("stream control frame.type must be text");
    }
    return switch (typeNode.stringValue()) {
      case "HTTP_RESPONSE" -> decodeHttpResponse(root);
      case "WS_READY" -> {
        rejectUnknownFields(root, WS_READY_FIELDS, "WS_READY");
        yield new IdeStreamControlFrame.WsReady();
      }
      case "ERROR" -> {
        rejectUnknownFields(root, ERROR_FIELDS, "ERROR");
        yield new IdeStreamControlFrame.Error(requiredText(root, "message", "ERROR"));
      }
      default -> throw new DaemonProtocolException(
          "unknown stream control frame type: " + typeNode.stringValue());
    };
  }

  private IdeStreamControlFrame decodeHttpResponse(ObjectNode root) {
    rejectUnknownFields(root, HTTP_RESPONSE_FIELDS, "HTTP_RESPONSE");
    JsonNode status = root.get("status");
    if (status == null || !status.canConvertToInt()) {
      throw new DaemonProtocolException("HTTP_RESPONSE.status must be an integer");
    }
    JsonNode headersNode = root.get("headers");
    if (headersNode == null || !headersNode.isArray()) {
      throw new DaemonProtocolException("HTTP_RESPONSE.headers must be an array");
    }
    List<IdeHeader> headers = new ArrayList<>();
    for (JsonNode entry : headersNode) {
      if (!entry.isArray()
          || entry.size() != 2
          || !entry.get(0).isString()
          || !entry.get(1).isString()) {
        throw new DaemonProtocolException("HTTP_RESPONSE header must be [name,value] strings");
      }
      try {
        headers.add(new IdeHeader(entry.get(0).stringValue(), entry.get(1).stringValue()));
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(
            "HTTP_RESPONSE header is invalid: " + error.getMessage(), error);
      }
    }
    try {
      return new IdeStreamControlFrame.HttpResponse(status.intValue(), headers);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "HTTP_RESPONSE validation failed: " + error.getMessage(), error);
    }
  }

  public byte[] encodeHttpBody(byte[] payload) {
    Objects.requireNonNull(payload, "payload");
    if (payload.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("stream payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
    }
    byte[] frame = new byte[payload.length + 1];
    frame[0] = FLAG_HTTP_BODY;
    System.arraycopy(payload, 0, frame, 1, payload.length);
    return frame;
  }

  public byte[] encodeHttpEnd() {
    return new byte[] {FLAG_HTTP_END};
  }

  public byte[] encodeAck(long byteCount) {
    if (byteCount < 0L || byteCount > 0xFFFFFFFFL) {
      throw new IllegalArgumentException("ack byteCount must be an unsigned 32-bit value");
    }
    byte[] frame = new byte[5];
    frame[0] = FLAG_ACK;
    frame[1] = (byte) (byteCount >>> 24);
    frame[2] = (byte) (byteCount >>> 16);
    frame[3] = (byte) (byteCount >>> 8);
    frame[4] = (byte) byteCount;
    return frame;
  }

  public byte[] encodeWsSegment(boolean text, boolean last, byte[] payload) {
    return encodePayload(text ? FLAG_WS_TEXT : FLAG_WS_BINARY, last, payload);
  }

  public IdeStreamBinaryFrame decodeBinary(byte[] frame) {
    Objects.requireNonNull(frame, "frame");
    if (frame.length == 0) {
      throw new DaemonProtocolException("stream binary frame must not be empty");
    }
    byte flag = frame[0];
    return switch (flag) {
      case FLAG_HTTP_BODY -> new IdeStreamBinaryFrame.HttpBody(payload(frame, 1));
      case FLAG_HTTP_END -> {
        if (frame.length != 1) {
          throw new DaemonProtocolException("HTTP END frame must carry no payload");
        }
        yield new IdeStreamBinaryFrame.HttpEnd();
      }
      case FLAG_ACK -> {
        if (frame.length != 5) {
          throw new DaemonProtocolException(
              "ACK frame must carry exactly an unsigned 32-bit value");
        }
        long byteCount =
            ((long) (frame[1] & 0xFF) << 24)
                | ((long) (frame[2] & 0xFF) << 16)
                | ((long) (frame[3] & 0xFF) << 8)
                | (long) (frame[4] & 0xFF);
        yield new IdeStreamBinaryFrame.Ack(byteCount);
      }
      case FLAG_WS_TEXT, FLAG_WS_BINARY -> {
        if (frame.length < 2 || (frame[1] != 0 && frame[1] != 1)) {
          throw new DaemonProtocolException("WS segment frame must declare a 0/1 FIN byte");
        }
        yield new IdeStreamBinaryFrame.WsSegment(
            flag == FLAG_WS_TEXT, frame[1] == 1, payload(frame, 2));
      }
      default -> throw new DaemonProtocolException("unknown stream binary frame flag: " + flag);
    };
  }

  private static byte[] encodePayload(byte flag, boolean last, byte[] payload) {
    Objects.requireNonNull(payload, "payload");
    if (payload.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("stream payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
    }
    byte[] frame = new byte[payload.length + 2];
    frame[0] = flag;
    frame[1] = (byte) (last ? 1 : 0);
    System.arraycopy(payload, 0, frame, 2, payload.length);
    return frame;
  }

  private static byte[] payload(byte[] frame, int offset) {
    int length = frame.length - offset;
    if (length > MAX_PAYLOAD_BYTES) {
      throw new DaemonProtocolException("stream payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
    }
    byte[] payload = new byte[length];
    System.arraycopy(frame, offset, payload, 0, length);
    return payload;
  }

  private static void rejectUnknownFields(ObjectNode root, Set<String> allowed, String context) {
    root.propertyNames()
        .forEach(
            field -> {
              if (!allowed.contains(field)) {
                throw new DaemonProtocolException(context + " has unknown field: " + field);
              }
            });
  }

  private static String requiredText(ObjectNode root, String field, String context) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString()) {
      throw new DaemonProtocolException(context + "." + field + " must be text");
    }
    return value.stringValue();
  }

  private static String write(ObjectNode root, String context) {
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("cannot encode stream " + context + " frame", error);
    }
  }
}

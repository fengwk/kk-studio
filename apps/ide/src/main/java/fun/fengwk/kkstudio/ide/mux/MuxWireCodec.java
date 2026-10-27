package fun.fengwk.kkstudio.ide.mux;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * IDE 数据 wire v2 的无状态严格 codec。
 *
 * <p>每帧 12 字节头为 {@code version:u8 | type:u8 | flags:u16 | streamId:u32 |
 * payloadLength:u32}，整数使用网络字节序。 解码拒绝未知版本/类型、非 DATA 的 flags、长度不符、越界 payload 以及非法 UTF-8；JSON
 * 元数据还拒绝重复键、未知键和缺失键。
 *
 * <p>codec 只负责形状与长度，不维护 credit、流顺序或连接状态；这些由 {@link MuxConnection} 裁决。运行在 Server 侧时，OPEN/READY
 * 等方向规则也由引擎按 Server 角色校验。
 */
public final class MuxWireCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> OPEN_FIELDS =
      Set.of("viewId", "bindingEpoch", "mode", "method", "path", "headers");
  private static final Set<String> HEADERS_FIELDS = Set.of("status", "headers");
  private static final Set<String> RESET_FIELDS = Set.of("code", "message");

  private static final int DATA_FLAG_TEXT = 0x1;
  private static final int DATA_FLAG_BINARY = 0x2;
  private static final int DATA_FLAG_FIN = 0x4;
  private static final int DATA_FLAGS_MASK = DATA_FLAG_TEXT | DATA_FLAG_BINARY | DATA_FLAG_FIN;

  /** 编码一帧为完整 wire 字节。 */
  public byte[] encode(MuxFrame frame) {
    Objects.requireNonNull(frame, "frame");
    byte[] payload;
    int flags = 0;
    switch (frame) {
      case MuxFrame.Open open -> {
        requireStreamId(open.streamId());
        payload = encodeOpen(open.request());
      }
      case MuxFrame.Ready ready -> {
        requireStreamId(ready.streamId());
        payload = new byte[0];
      }
      case MuxFrame.Headers headers -> {
        requireStreamId(headers.streamId());
        payload = encodeHeaders(headers.headers());
      }
      case MuxFrame.Data data -> {
        requireStreamId(data.streamId());
        flags = encodeDataFlags(data);
        payload = data.payload();
        if (payload.length > MuxLimits.MAX_PAYLOAD_BYTES) {
          throw new IllegalArgumentException(
              "payload exceeds " + MuxLimits.MAX_PAYLOAD_BYTES + " bytes");
        }
      }
      case MuxFrame.Window window -> payload = encodeWindow(window.increment());
      case MuxFrame.End end -> {
        requireStreamId(end.streamId());
        payload = new byte[0];
      }
      case MuxFrame.Complete complete -> {
        requireStreamId(complete.streamId());
        payload = new byte[0];
      }
      case MuxFrame.Reset reset -> {
        requireStreamId(reset.streamId());
        payload = encodeReset(reset.reset());
      }
      case MuxFrame.Close close -> {
        requireStreamId(close.streamId());
        payload = encodeClose(close.close());
      }
    }
    if (payload.length > MuxLimits.MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException(
          "payload exceeds " + MuxLimits.MAX_PAYLOAD_BYTES + " bytes");
    }
    byte[] wire = new byte[MuxLimits.HEADER_BYTES + payload.length];
    wire[0] = (byte) MuxLimits.WIRE_VERSION;
    wire[1] = (byte) frame.type().code();
    writeU16(wire, 2, flags);
    writeU32(wire, 4, frame.streamId());
    writeU32(wire, 8, payload.length);
    System.arraycopy(payload, 0, wire, MuxLimits.HEADER_BYTES, payload.length);
    return wire;
  }

  /** 解码一条完整 wire 帧；任何协议形态错误都抛 {@link MuxProtocolException}。 */
  public MuxFrame decode(byte[] wire) {
    Objects.requireNonNull(wire, "wire");
    if (wire.length < MuxLimits.HEADER_BYTES) {
      throw new MuxProtocolException("mux frame is shorter than the 12-byte header");
    }
    int version = wire[0] & 0xFF;
    if (version != MuxLimits.WIRE_VERSION) {
      throw new MuxProtocolException("unsupported mux wire version: " + version);
    }
    MuxFrameType type;
    try {
      type = MuxFrameType.fromCode(wire[1] & 0xFF);
    } catch (IllegalArgumentException error) {
      throw new MuxProtocolException(error.getMessage(), error);
    }
    int flags = readU16(wire, 2);
    long streamId = readU32(wire, 4);
    long payloadLength = readU32(wire, 8);
    if (payloadLength != wire.length - MuxLimits.HEADER_BYTES) {
      throw new MuxProtocolException("payloadLength does not match the received frame length");
    }
    if (payloadLength > MuxLimits.MAX_PAYLOAD_BYTES) {
      throw new MuxProtocolException(
          "mux frame payload exceeds " + MuxLimits.MAX_PAYLOAD_BYTES + " bytes");
    }
    byte[] payload =
        Arrays.copyOfRange(
            wire, MuxLimits.HEADER_BYTES, MuxLimits.HEADER_BYTES + (int) payloadLength);
    try {
      return switch (type) {
        case OPEN -> decodeOpen(streamId, flags, payload);
        case READY -> decodeEmpty(streamId, flags, payload, new MuxFrame.Ready(streamId));
        case HEADERS -> decodeHeaders(streamId, flags, payload);
        case DATA -> decodeData(streamId, flags, payload);
        case WINDOW -> decodeWindow(streamId, flags, payload);
        case END -> decodeEmpty(streamId, flags, payload, new MuxFrame.End(streamId));
        case COMPLETE -> decodeEmpty(streamId, flags, payload, new MuxFrame.Complete(streamId));
        case RESET -> decodeReset(streamId, flags, payload);
        case CLOSE -> decodeClose(streamId, flags, payload);
      };
    } catch (IllegalArgumentException error) {
      throw new MuxProtocolException("invalid mux frame: " + error.getMessage(), error);
    }
  }

  private MuxFrame decodeOpen(long streamId, int flags, byte[] payload) {
    requireNoFlags(flags, MuxFrameType.OPEN);
    requireStreamId(streamId);
    requireJsonSize(payload, MuxFrameType.OPEN);
    ObjectNode root = readObject(payload, "OPEN");
    requireFields(root, OPEN_FIELDS, "OPEN");
    JsonNode viewId = required(root, "viewId", "OPEN");
    JsonNode bindingEpoch = required(root, "bindingEpoch", "OPEN");
    JsonNode mode = required(root, "mode", "OPEN");
    JsonNode method = required(root, "method", "OPEN");
    JsonNode path = required(root, "path", "OPEN");
    if (!viewId.isString()
        || !bindingEpoch.isString()
        || !mode.isString()
        || !method.isString()
        || !path.isString()) {
      throw new MuxProtocolException("OPEN scalar fields must be text");
    }
    MuxMode decodedMode;
    try {
      decodedMode = MuxMode.valueOf(mode.stringValue());
    } catch (IllegalArgumentException error) {
      throw new MuxProtocolException("OPEN mode must be HTTP or WS", error);
    }
    MuxOpen request =
        new MuxOpen(
            MuxWireRules.requireViewId(viewId.stringValue()),
            MuxWireRules.requireBindingEpoch(bindingEpoch.stringValue()),
            decodedMode,
            method.stringValue(),
            path.stringValue(),
            decodeHeadersArray(
                required(root, "headers", "OPEN"), "OPEN", MuxLimits.MAX_OPEN_HEADERS));
    return new MuxFrame.Open(streamId, request);
  }

  private MuxFrame decodeHeaders(long streamId, int flags, byte[] payload) {
    requireNoFlags(flags, MuxFrameType.HEADERS);
    requireStreamId(streamId);
    requireJsonSize(payload, MuxFrameType.HEADERS);
    ObjectNode root = readObject(payload, "HEADERS");
    requireFields(root, HEADERS_FIELDS, "HEADERS");
    JsonNode status = required(root, "status", "HEADERS");
    if (!status.isIntegralNumber() || !status.canConvertToInt()) {
      throw new MuxProtocolException("HEADERS.status must be an integer");
    }
    MuxHeaders headers =
        new MuxHeaders(
            status.intValue(),
            decodeHeadersArray(required(root, "headers", "HEADERS"), "HEADERS", Integer.MAX_VALUE));
    return new MuxFrame.Headers(streamId, headers);
  }

  private MuxFrame decodeData(long streamId, int flags, byte[] payload) {
    requireStreamId(streamId);
    if (payload.length > MuxLimits.MAX_PAYLOAD_BYTES) {
      throw new MuxProtocolException(
          "DATA payload exceeds " + MuxLimits.MAX_PAYLOAD_BYTES + " bytes");
    }
    if ((flags & ~DATA_FLAGS_MASK) != 0) {
      throw new MuxProtocolException("DATA contains undefined flag bits");
    }
    MuxDataKind kind;
    boolean fin;
    switch (flags) {
      case 0 -> {
        kind = MuxDataKind.HTTP;
        fin = false;
      }
      case DATA_FLAG_TEXT -> {
        kind = MuxDataKind.WS_TEXT;
        fin = false;
      }
      case DATA_FLAG_BINARY -> {
        kind = MuxDataKind.WS_BINARY;
        fin = false;
      }
      case DATA_FLAG_TEXT | DATA_FLAG_FIN -> {
        kind = MuxDataKind.WS_TEXT;
        fin = true;
      }
      case DATA_FLAG_BINARY | DATA_FLAG_FIN -> {
        kind = MuxDataKind.WS_BINARY;
        fin = true;
      }
      default -> throw new MuxProtocolException(
          "DATA flags must be HTTP(0) or exactly one of WS TEXT/BINARY plus optional FIN");
    }
    return new MuxFrame.Data(streamId, kind, fin, payload);
  }

  private MuxFrame decodeWindow(long streamId, int flags, byte[] payload) {
    requireNoFlags(flags, MuxFrameType.WINDOW);
    if (payload.length != 4) {
      throw new MuxProtocolException("WINDOW payload must be exactly four bytes");
    }
    long increment = readU32(payload, 0);
    if (increment == 0) {
      throw new MuxProtocolException("WINDOW increment must be positive");
    }
    return new MuxFrame.Window(streamId, increment);
  }

  private MuxFrame decodeReset(long streamId, int flags, byte[] payload) {
    requireNoFlags(flags, MuxFrameType.RESET);
    requireStreamId(streamId);
    requireJsonSize(payload, MuxFrameType.RESET);
    ObjectNode root = readObject(payload, "RESET");
    requireFields(root, RESET_FIELDS, "RESET");
    JsonNode code = required(root, "code", "RESET");
    JsonNode message = required(root, "message", "RESET");
    if (!code.isString() || !message.isString()) {
      throw new MuxProtocolException("RESET code and message must be text");
    }
    return new MuxFrame.Reset(streamId, new MuxReset(code.stringValue(), message.stringValue()));
  }

  private MuxFrame decodeClose(long streamId, int flags, byte[] payload) {
    requireNoFlags(flags, MuxFrameType.CLOSE);
    requireStreamId(streamId);
    if (payload.length < 2 || payload.length > 2 + MuxLimits.MAX_CLOSE_REASON_BYTES) {
      throw new MuxProtocolException("CLOSE payload must be a code plus at most 123 reason bytes");
    }
    int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
    String reason =
        decodeUtf8(Arrays.copyOfRange(payload, 2, payload.length), "CLOSE reason", false);
    return new MuxFrame.Close(streamId, new MuxClose(code, reason));
  }

  private MuxFrame decodeEmpty(long streamId, int flags, byte[] payload, MuxFrame frame) {
    requireNoFlags(flags, frame.type());
    requireStreamId(streamId);
    if (payload.length != 0) {
      throw new MuxProtocolException(frame.type() + " payload must be empty");
    }
    return frame;
  }

  private static int encodeDataFlags(MuxFrame.Data data) {
    int flags =
        switch (data.kind()) {
          case HTTP -> 0;
          case WS_TEXT -> DATA_FLAG_TEXT;
          case WS_BINARY -> DATA_FLAG_BINARY;
        };
    return data.fin() ? flags | DATA_FLAG_FIN : flags;
  }

  private static byte[] encodeOpen(MuxOpen request) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("viewId", request.viewId().toString());
    root.put("bindingEpoch", Long.toString(request.bindingEpoch()));
    root.put("mode", MuxWireRules.canonicalMode(request.mode()));
    root.put("method", request.method());
    root.put("path", request.path());
    root.set("headers", encodeHeadersArray(request.headers()));
    return writeJson(root, "OPEN");
  }

  private static byte[] encodeHeaders(MuxHeaders headers) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("status", headers.status());
    root.set("headers", encodeHeadersArray(headers.headers()));
    return writeJson(root, "HEADERS");
  }

  private static byte[] encodeReset(MuxReset reset) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("code", reset.code());
    root.put("message", reset.message());
    return writeJson(root, "RESET");
  }

  private static byte[] encodeClose(MuxClose close) {
    byte[] reason = close.reason().getBytes(StandardCharsets.UTF_8);
    if (reason.length > MuxLimits.MAX_CLOSE_REASON_BYTES) {
      throw new IllegalArgumentException(
          "close reason exceeds " + MuxLimits.MAX_CLOSE_REASON_BYTES + " UTF-8 bytes");
    }
    byte[] payload = new byte[2 + reason.length];
    payload[0] = (byte) (close.code() >>> 8);
    payload[1] = (byte) close.code();
    System.arraycopy(reason, 0, payload, 2, reason.length);
    return payload;
  }

  private static byte[] encodeWindow(long increment) {
    if (increment <= 0 || increment > MuxFrame.MAX_STREAM_ID) {
      throw new IllegalArgumentException("WINDOW increment must be a positive uint32");
    }
    byte[] payload = new byte[4];
    writeU32(payload, 0, increment);
    return payload;
  }

  private static ArrayNode encodeHeadersArray(List<MuxHeader> headers) {
    ArrayNode array = MAPPER.createArrayNode();
    for (MuxHeader header : headers) {
      ArrayNode pair = array.addArray();
      pair.add(header.name());
      pair.add(header.value());
    }
    return array;
  }

  private static List<MuxHeader> decodeHeadersArray(JsonNode node, String context, int maxItems) {
    if (!node.isArray()) {
      throw new MuxProtocolException(context + ".headers must be an array");
    }
    if (node.size() > maxItems) {
      throw new MuxProtocolException(
          context + ".headers must contain at most " + maxItems + " items");
    }
    List<MuxHeader> headers = new ArrayList<>(node.size());
    for (JsonNode entry : node) {
      if (!entry.isArray()
          || entry.size() != 2
          || !entry.get(0).isString()
          || !entry.get(1).isString()) {
        throw new MuxProtocolException(context + " header must be a [name,value] string pair");
      }
      headers.add(new MuxHeader(entry.get(0).stringValue(), entry.get(1).stringValue()));
    }
    return List.copyOf(headers);
  }

  private static ObjectNode readObject(byte[] payload, String context) {
    String json = decodeUtf8(payload, context + " JSON");
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new MuxProtocolException("malformed " + context + " JSON", error);
    }
    if (value == null || !value.isObject()) {
      throw new MuxProtocolException(context + " JSON must be an object");
    }
    return (ObjectNode) value;
  }

  private static void requireFields(ObjectNode root, Set<String> expected, String context) {
    for (String name : root.propertyNames()) {
      if (!expected.contains(name)) {
        throw new MuxProtocolException(context + " contains unknown field: " + name);
      }
    }
    for (String name : expected) {
      if (!root.has(name)) {
        throw new MuxProtocolException(context + " is missing field: " + name);
      }
    }
  }

  private static JsonNode required(ObjectNode root, String name, String context) {
    JsonNode node = root.get(name);
    if (node == null) {
      throw new MuxProtocolException(context + " is missing field: " + name);
    }
    return node;
  }

  private static void requireJsonSize(byte[] payload, MuxFrameType type) {
    if (payload.length > MuxLimits.MAX_JSON_BYTES) {
      throw new MuxProtocolException(type + " JSON exceeds " + MuxLimits.MAX_JSON_BYTES + " bytes");
    }
  }

  private static void requireNoFlags(int flags, MuxFrameType type) {
    if (flags != 0) {
      throw new MuxProtocolException(type + " flags must be zero");
    }
  }

  private static void requireStreamId(long streamId) {
    if (streamId <= 0 || streamId > MuxFrame.MAX_STREAM_ID) {
      throw new MuxProtocolException("streamId must be within [1, 2^32-1]");
    }
  }

  private static byte[] writeJson(ObjectNode root, String context) {
    byte[] bytes;
    try {
      bytes = MAPPER.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode " + context + " JSON", error);
    }
    if (bytes.length > MuxLimits.MAX_JSON_BYTES) {
      throw new IllegalArgumentException(
          context + " JSON exceeds " + MuxLimits.MAX_JSON_BYTES + " bytes");
    }
    return bytes;
  }

  private static String decodeUtf8(byte[] bytes, String context) {
    return decodeUtf8(bytes, context, true);
  }

  private static String decodeUtf8(byte[] bytes, String context, boolean rejectBom) {
    if (rejectBom
        && bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xEF
        && (bytes[1] & 0xFF) == 0xBB
        && (bytes[2] & 0xFF) == 0xBF) {
      throw new MuxProtocolException(context + " must not start with a UTF-8 BOM");
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException error) {
      throw new MuxProtocolException(context + " is not valid UTF-8", error);
    }
  }

  private static int readU16(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
  }

  private static long readU32(byte[] bytes, int offset) {
    return ((long) (bytes[offset] & 0xFF) << 24)
        | ((long) (bytes[offset + 1] & 0xFF) << 16)
        | ((long) (bytes[offset + 2] & 0xFF) << 8)
        | (long) (bytes[offset + 3] & 0xFF);
  }

  private static void writeU16(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) (value >>> 8);
    bytes[offset + 1] = (byte) value;
  }

  private static void writeU32(byte[] bytes, int offset, long value) {
    bytes[offset] = (byte) (value >>> 24);
    bytes[offset + 1] = (byte) (value >>> 16);
    bytes[offset + 2] = (byte) (value >>> 8);
    bytes[offset + 3] = (byte) value;
  }
}

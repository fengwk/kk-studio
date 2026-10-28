package fun.fengwk.kkstudio.ide.mux;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 意图：wire v2 必须跨语言逐字节可判定 —— 12 字节头、类型/flags 组合、JSON 字段集合与全部上限都在边界 fail-closed；fixtures 固定
 * OPEN/HEADERS/RESET 的 canonical 形状，避免 Java 与 Node 漂移。
 */
class MuxWireCodecTest {

  private static final MuxWireCodec CODEC = new MuxWireCodec();
  private static final UUID VIEW_ID = UUID.fromString("2a4e6b6e-8f1c-4d8a-9c2d-5b7e1f3a9c01");

  @Test
  void canonicalFixturesRoundTripWithoutShapeDrift() {
    MuxFrame.Open openHttp =
        assertInstanceOf(
            MuxFrame.Open.class, CODEC.decode(wire(1, 0, 7, fixture("open-http.json"))));
    assertEquals(VIEW_ID, openHttp.request().viewId());
    assertEquals(7L, openHttp.request().bindingEpoch());
    assertEquals(MuxMode.HTTP, openHttp.request().mode());
    assertEquals("POST", openHttp.request().method());
    assertEquals(3, openHttp.request().headers().size());
    assertArrayEquals(fixture("open-http.json"), payload(CODEC.encode(openHttp)));

    MuxFrame.Open openWs =
        assertInstanceOf(MuxFrame.Open.class, CODEC.decode(wire(1, 0, 8, fixture("open-ws.json"))));
    assertEquals(MuxMode.WS, openWs.request().mode());
    assertArrayEquals(fixture("open-ws.json"), payload(CODEC.encode(openWs)));

    MuxFrame.Headers headers =
        assertInstanceOf(
            MuxFrame.Headers.class, CODEC.decode(wire(3, 0, 7, fixture("headers.json"))));
    assertEquals(200, headers.headers().status());
    assertEquals(3, headers.headers().headers().get(1).value().length());
    assertArrayEquals(fixture("headers.json"), payload(CODEC.encode(headers)));

    MuxFrame.Reset reset =
        assertInstanceOf(MuxFrame.Reset.class, CODEC.decode(wire(8, 0, 7, fixture("reset.json"))));
    assertEquals("UPSTREAM_UNAVAILABLE", reset.reset().code());
    assertArrayEquals(fixture("reset.json"), payload(CODEC.encode(reset)));
  }

  @Test
  void roundTripsEveryFrameTypeAndWriteHeaderBytes() {
    List<MuxFrame> frames =
        List.of(
            new MuxFrame.Open(7, openHttp()),
            new MuxFrame.Ready(7),
            new MuxFrame.Headers(7, MuxHeaders.http(200, List.of(new MuxHeader("A", "b")))),
            new MuxFrame.Data(7, MuxDataKind.HTTP, false, bytes("body")),
            new MuxFrame.Data(7, MuxDataKind.WS_TEXT, false, bytes("a")),
            new MuxFrame.Data(7, MuxDataKind.WS_TEXT, true, new byte[0]),
            new MuxFrame.Data(7, MuxDataKind.WS_BINARY, false, bytes("b")),
            new MuxFrame.Data(7, MuxDataKind.WS_BINARY, true, bytes("c")),
            new MuxFrame.Window(0, 512),
            new MuxFrame.Window(7, 0xFFFFFFFFL),
            new MuxFrame.End(7),
            new MuxFrame.Complete(7),
            new MuxFrame.Reset(7, new MuxReset("UPSTREAM_FAILED", "failed")),
            new MuxFrame.Close(7, new MuxClose(1000, "bye")),
            new MuxFrame.Close(7, new MuxClose(1000, "\uFEFFbom")));
    for (MuxFrame frame : frames) {
      byte[] wire = CODEC.encode(frame);
      assertEquals(MuxLimits.WIRE_VERSION, wire[0]);
      assertEquals(frame.type().code(), wire[1]);
      assertEquals(0, wire[2]);
      if (frame.type() != MuxFrameType.DATA) {
        assertEquals(0, wire[3]);
      }
      assertFrameEquals(frame, CODEC.decode(wire));
    }

    byte[] close = CODEC.encode(new MuxFrame.Close(0x01020304L, new MuxClose(1000, "bye")));
    assertArrayEquals(
        new byte[] {2, 9, 0, 0, 1, 2, 3, 4, 0, 0, 0, 5, 3, (byte) 0xE8, 'b', 'y', 'e'}, close);
  }

  @Test
  void rejectsMalformedHeaderVersionTypeFlagsAndLengths() {
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(new byte[11]));
    byte[] badVersion = wire(2, 0, 1, new byte[0]);
    badVersion[0] = 3;
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(badVersion));
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(99, 0, 1, new byte[0])));
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(2, 1, 1, new byte[0])));
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(5, 1, 0, new byte[4])));
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(2, 0, 1, new byte[1])));
    assertThrows(
        MuxProtocolException.class, () -> CODEC.decode(wire(1, 0, 0, fixture("open-http.json"))));
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(5, 0, 0, new byte[3])));
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(5, 0, 0, new byte[5])));
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(5, 0, 0, new byte[4])));
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(9, 0, 1, new byte[1])));
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(9, 0, 1, new byte[126])));
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(4, 0, 1, new byte[MuxLimits.MAX_PAYLOAD_BYTES + 1])));

    byte[] extra = new byte[MuxLimits.HEADER_BYTES + 1];
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(extra));
  }

  @Test
  void rejectsUndefinedDataFlagCombinations() {
    for (int flags : new int[] {3, 4, 7, 8, 0x10}) {
      assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(4, flags, 1, new byte[0])));
    }
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(4, 0, 1, new byte[MuxLimits.MAX_PAYLOAD_BYTES + 1])));
  }

  @Test
  void rejectsStrictJsonFixtures() {
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(1, 0, 1, fixture("invalid-open-unknown-key.json"))));
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(1, 0, 1, fixture("invalid-open-duplicate-key.json"))));
    assertThrows(
        MuxProtocolException.class,
        () ->
            CODEC.decode(
                wire(8, 0, 1, bytes("{\"code\":\"A\",\"\\u0063ode\":\"B\",\"message\":\"x\"}"))));
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(1, 0, 1, fixture("invalid-open-uppercase-view-id.json"))));
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(1, 0, 1, fixture("invalid-open-leading-zero-epoch.json"))));
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(1, 0, 1, fixture("invalid-open-path.json"))));
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(8, 0, 1, fixture("invalid-reset-url.json"))));
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(3, 0, 1, fixture("invalid-headers-unknown-key.json"))));
  }

  @Test
  void rejectsNonStandardJsonExtensions() {
    String prefix = "{\"status\":";
    String suffix = ",\"headers\":[]}";
    for (String status : List.of("0200", "+200", "0xC8", "200,", "200/*comment*/")) {
      assertThrows(
          MuxProtocolException.class,
          () -> CODEC.decode(wire(3, 0, 1, bytes(prefix + status + suffix))));
    }
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(3, 0, 1, bytes("{'status':200,'headers':[]}"))));
  }

  @Test
  void rejectsInvalidUtf8BomAndTrailingJson() {
    byte[] invalidUtf8 = {(byte) 0xC3, 0x28};
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(1, 0, 1, invalidUtf8)));
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'};
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(1, 0, 1, bom)));
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(1, 0, 1, bytes("{} {}"))));
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(3, 0, 1, bytes("{\"status\":\"200\",\"headers\":[]}"))));
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(3, 0, 1, bytes("{\"status\":200.5,\"headers\":[]}"))));
  }

  @Test
  void validatesOpenFieldsAndBounds() {
    List<MuxHeader> headers = List.of(new MuxHeader("A", "b"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MuxOpen(VIEW_ID, 0, MuxMode.HTTP, "GET", "/x", headers));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MuxOpen(VIEW_ID, 1, MuxMode.HTTP, "BAD METHOD", "/x", headers));
    assertEquals(MuxMode.WS, new MuxOpen(VIEW_ID, 1, MuxMode.WS, "POST", "/x", headers).mode());
    assertThrows(
        IllegalArgumentException.class,
        () -> new MuxOpen(VIEW_ID, 1, MuxMode.HTTP, "G".repeat(33), "/x", headers));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MuxOpen(VIEW_ID, 1, MuxMode.HTTP, "GET", "x", headers));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MuxOpen(VIEW_ID, 1, MuxMode.HTTP, "GET", "/x\r\n", headers));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MuxOpen(VIEW_ID, 1, MuxMode.HTTP, "GET", "/" + "a".repeat(8192), headers));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MuxOpen(VIEW_ID, 1, MuxMode.HTTP, "GET", "/x", repeatHeader(101)));
    assertThrows(IllegalArgumentException.class, () -> new MuxHeader("Bad Name", "x"));
    assertThrows(IllegalArgumentException.class, () -> new MuxHeader("A", "x\r\ny"));
    assertThrows(IllegalArgumentException.class, () -> new MuxHeader("A", "x\u0000"));
    assertEquals("x\ty", new MuxHeader("A", "x\ty").value());
    assertThrows(IllegalArgumentException.class, () -> MuxLimits.dataCost(-1));
  }

  @Test
  void rejectsOversizedJsonAndHeaderLists() {
    MuxHeader huge = new MuxHeader("A", "x".repeat(MuxLimits.MAX_JSON_BYTES));
    MuxOpen open = new MuxOpen(VIEW_ID, 1, MuxMode.HTTP, "GET", "/x", List.of(huge));
    assertThrows(IllegalArgumentException.class, () -> CODEC.encode(new MuxFrame.Open(1, open)));

    byte[] oversized = new byte[MuxLimits.MAX_JSON_BYTES + 1];
    oversized[0] = '{';
    oversized[oversized.length - 1] = '}';
    assertThrows(MuxProtocolException.class, () -> CODEC.decode(wire(1, 0, 1, oversized)));

    MuxOpen tooManyHeaders = new MuxOpen(VIEW_ID, 1, MuxMode.HTTP, "GET", "/x", new ArrayList<>());
    MuxFrame.Headers manyHeaders = new MuxFrame.Headers(1, new MuxHeaders(200, repeatHeader(101)));
    assertEquals(101, manyHeaders.headers().headers().size());
    assertEquals(
        101,
        ((MuxFrame.Headers) CODEC.decode(CODEC.encode(manyHeaders))).headers().headers().size());
    assertThrows(IllegalArgumentException.class, () -> new MuxHeaders(99, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new MuxHeaders(600, List.of()));
    assertEquals(MuxMode.HTTP, tooManyHeaders.mode());
    assertEquals(101, MuxHeaders.webSocket(List.of()).status());
    assertEquals(204, MuxHeaders.http(204, List.of()).status());
  }

  @Test
  void validatesResetAndCloseBounds() {
    assertThrows(IllegalArgumentException.class, () -> new MuxReset("lower", "m"));
    assertThrows(IllegalArgumentException.class, () -> new MuxReset("A".repeat(65), "m"));
    assertThrows(IllegalArgumentException.class, () -> new MuxReset("OK", "line\nbreak"));
    assertThrows(IllegalArgumentException.class, () -> new MuxReset("OK", "x".repeat(501)));
    assertThrows(IllegalArgumentException.class, () -> new MuxReset("A-B", "m"));
    assertThrows(IllegalArgumentException.class, () -> new MuxReset("OK", "x\u0085y"));
    assertThrows(
        IllegalArgumentException.class, () -> new MuxReset("OK", "\uD83D\uDE00".repeat(251)));
    assertThrows(
        IllegalArgumentException.class, () -> new MuxReset("OK", "Authorization: Bearer secret"));
    assertThrows(IllegalArgumentException.class, () -> new MuxReset("OK", "http://host/x"));
    assertThrows(IllegalArgumentException.class, () -> new MuxClose(1004, ""));
    assertThrows(IllegalArgumentException.class, () -> new MuxClose(1005, ""));
    assertThrows(IllegalArgumentException.class, () -> new MuxClose(1006, ""));
    assertThrows(IllegalArgumentException.class, () -> new MuxClose(1015, ""));
    assertThrows(IllegalArgumentException.class, () -> new MuxClose(2999, ""));
    assertThrows(IllegalArgumentException.class, () -> new MuxClose(5000, ""));
    assertThrows(IllegalArgumentException.class, () -> new MuxClose(1000, "x".repeat(124)));
    assertEquals(4999, new MuxClose(4999, "").code());
  }

  @Test
  void validatesFrameValueObjectsAndTypes() {
    assertThrows(IllegalArgumentException.class, () -> new MuxFrame.Ready(0));
    assertThrows(IllegalArgumentException.class, () -> new MuxFrame.Open(0, openHttp()));
    assertThrows(IllegalArgumentException.class, () -> new MuxFrame.Window(0, 0));
    assertThrows(IllegalArgumentException.class, () -> new MuxFrame.Window(0, 0x1_0000_0000L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MuxFrame.Data(1, MuxDataKind.HTTP, true, new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MuxFrame.Data(
                1, MuxDataKind.HTTP, false, new byte[MuxLimits.MAX_PAYLOAD_BYTES + 1]));
    assertEquals(MuxFrameType.OPEN, MuxFrameType.fromCode(1));
    assertThrows(IllegalArgumentException.class, () -> MuxFrameType.fromCode(0));
    assertEquals(512, MuxLimits.dataCost(0));
    assertEquals(513, MuxLimits.dataCost(513));
  }

  @Test
  void coversDefaultCallbacksAndRemainingSyntaxGuards() {
    MuxStreamListener streamListener = new MuxStreamListener() {};
    streamListener.onReady(null);
    streamListener.onHeaders(null, null);
    streamListener.onData(null, null);
    streamListener.onEnd(null);
    streamListener.onReset(null, null);
    streamListener.onClose(null, null);
    MuxConnectionListener connectionListener = new MuxConnectionListener() {};
    connectionListener.onFailure(null, null);

    assertThrows(IllegalArgumentException.class, () -> new MuxHeader(null, "x"));
    assertThrows(IllegalArgumentException.class, () -> new MuxHeader("", "x"));
    assertThrows(IllegalArgumentException.class, () -> new MuxHeader("A", null));
    assertThrows(IllegalArgumentException.class, () -> new MuxHeaders(200, null));
    assertThrows(
        NullPointerException.class, () -> new MuxHeaders(200, Arrays.asList((MuxHeader) null)));
    assertThrows(IllegalArgumentException.class, () -> new MuxReset("A!", "m"));
    assertThrows(IllegalArgumentException.class, () -> new MuxReset("OK", null));
    assertThrows(IllegalArgumentException.class, () -> new MuxReset("OK", "\uD800"));
    assertThrows(IllegalArgumentException.class, () -> new MuxReset("OK", "a\uDC00"));
    assertEquals("OK", new MuxReset("OK", "smile \uD83D\uDE00").code());
    assertThrows(IllegalArgumentException.class, () -> new MuxClose(1000, null));
    assertThrows(IllegalArgumentException.class, () -> new MuxFrame.Window(-1, 1));
    assertEquals(MuxMode.HTTP, MuxOpen.http(VIEW_ID, 1, "GET", "/x", List.of()).mode());
    assertEquals(MuxMode.WS, MuxOpen.webSocket(VIEW_ID, 1, "/x", List.of()).mode());

    String overflowEpoch =
        "{\"viewId\":\"2a4e6b6e-8f1c-4d8a-9c2d-5b7e1f3a9c01\","
            + "\"bindingEpoch\":\"9223372036854775808\","
            + "\"mode\":\"HTTP\",\"method\":\"GET\",\"path\":\"/x\","
            + "\"headers\":[]}";
    assertThrows(
        MuxProtocolException.class,
        () -> CODEC.decode(wire(1, 0, 1, overflowEpoch.getBytes(StandardCharsets.UTF_8))));
  }

  private static MuxOpen openHttp() {
    return MuxOpen.http(
        VIEW_ID,
        7,
        "POST",
        "/vscode-remote-resource?path=%2Fsrc%2Fa.ts",
        List.of(
            new MuxHeader("Content-Type", "text/plain"),
            new MuxHeader("X-Trace", "a"),
            new MuxHeader("X-Trace", "b")));
  }

  private static List<MuxHeader> repeatHeader(int count) {
    List<MuxHeader> headers = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      headers.add(new MuxHeader("A", Integer.toString(i)));
    }
    return headers;
  }

  private static void assertFrameEquals(MuxFrame expected, MuxFrame actual) {
    assertEquals(expected.type(), actual.type());
    assertEquals(expected.streamId(), actual.streamId());
    if (expected instanceof MuxFrame.Data expectedData) {
      MuxFrame.Data actualData = assertInstanceOf(MuxFrame.Data.class, actual);
      assertEquals(expectedData.kind(), actualData.kind());
      assertEquals(expectedData.fin(), actualData.fin());
      assertArrayEquals(expectedData.payload(), actualData.payload());
    } else {
      assertEquals(expected, actual);
    }
  }

  private static byte[] wire(int type, int flags, long streamId, byte[] payload) {
    byte[] wire = new byte[MuxLimits.HEADER_BYTES + payload.length];
    wire[0] = 2;
    wire[1] = (byte) type;
    wire[2] = (byte) (flags >>> 8);
    wire[3] = (byte) flags;
    wire[4] = (byte) (streamId >>> 24);
    wire[5] = (byte) (streamId >>> 16);
    wire[6] = (byte) (streamId >>> 8);
    wire[7] = (byte) streamId;
    wire[8] = (byte) (payload.length >>> 24);
    wire[9] = (byte) (payload.length >>> 16);
    wire[10] = (byte) (payload.length >>> 8);
    wire[11] = (byte) payload.length;
    System.arraycopy(payload, 0, wire, MuxLimits.HEADER_BYTES, payload.length);
    return wire;
  }

  private static byte[] payload(byte[] wire) {
    return Arrays.copyOfRange(wire, MuxLimits.HEADER_BYTES, wire.length);
  }

  private static byte[] fixture(String name) {
    try (InputStream input =
        MuxWireCodecTest.class.getResourceAsStream("/fun/fengwk/kkstudio/ide/mux/" + name)) {
      if (input == null) {
        throw new IllegalStateException("missing fixture " + name);
      }
      String json = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
      return json.getBytes(StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException("cannot read fixture " + name, error);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}

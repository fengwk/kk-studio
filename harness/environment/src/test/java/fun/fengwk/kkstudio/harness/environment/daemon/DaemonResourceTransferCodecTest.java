package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceTransferCodec.TicketState;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceTransferCodec.UploadCommit;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceTransferCodec.UploadRequest;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceTransferCodec.UploadTicket;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Daemon 直传资源控制 payload 的双向与严格 shape 契约测试。 */
class DaemonResourceTransferCodecTest {

  private static final String SHA256 =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  private final DaemonResourceTransferCodec codec = new DaemonResourceTransferCodec();

  /** REQUEST 必须完整保留 transfer 身份与资源元数据，包括显式 null 文件名。 */
  @Test
  void roundTripsUploadRequest() {
    UploadRequest request = new UploadRequest(UUID.randomUUID(), "image/png", null, 123L, SHA256);

    String payload = codec.encodeRequest(request);

    assertEquals(request, codec.decodeRequest(payload));
    assertEquals(request.transferId(), codec.transferIdOf(payload));
  }

  /** COMMIT 必须同时绑定 transferId 与服务端签发的 uploadId。 */
  @Test
  void roundTripsUploadCommit() {
    UploadCommit commit = new UploadCommit(UUID.randomUUID(), UUID.randomUUID());

    assertEquals(commit, codec.decodeCommit(codec.encodeCommit(commit)));
  }

  /** PENDING 票据必须无损保留 PUT 方法、URL、精确签名 headers 与规范过期时刻。 */
  @Test
  void roundTripsPendingTicket() {
    UUID transferId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    DaemonPresignedPut presignedPut =
        new DaemonPresignedPut(
            "PUT",
            "https://storage.example.test/upload?signature=fake",
            Map.of("x-amz-checksum-sha256", "checksum", "content-length", "123"),
            Instant.parse("2026-07-28T10:01:00Z"));
    UploadTicket ticket = UploadTicket.pending(transferId, uploadId, presignedPut);

    UploadTicket decoded = codec.decodeTicket(codec.encodeTicket(ticket));

    assertEquals(ticket, decoded);
    assertNull(decoded.message());
  }

  /** READY 与 FAILED 票据使用互斥字段；FAILED 工厂会在编码前收敛为有界说明。 */
  @Test
  void roundTripsReadyAndBoundedFailedTickets() {
    UploadTicket ready = UploadTicket.ready(UUID.randomUUID(), UUID.randomUUID());
    assertEquals(ready, codec.decodeTicket(codec.encodeTicket(ready)));

    UploadTicket failed =
        UploadTicket.failed(
            UUID.randomUUID(),
            "x".repeat(DaemonResourceTransferCodec.MAX_FAILURE_MESSAGE_CHARS + 100));
    assertEquals(DaemonResourceTransferCodec.MAX_FAILURE_MESSAGE_CHARS, failed.message().length());
    assertEquals(failed, codec.decodeTicket(codec.encodeTicket(failed)));
  }

  /** 未知/缺失/重复字段、尾随 JSON 与状态不匹配字段必须在进入状态机前严格拒绝。 */
  @Test
  void rejectsAmbiguousOrMismatchedShapes() {
    UUID transferId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    String request =
        "{\"transferId\":\""
            + transferId
            + "\",\"mediaType\":\"image/png\",\"name\":null,\"size\":1,\"sha256\":\""
            + SHA256
            + "\"}";

    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeRequest(request.substring(0, request.length() - 1) + ",\"extra\":1}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeRequest(request.replace("\"name\":null,", "")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeRequest(
                request.replace(
                    "\"transferId\":\"" + transferId + "\"",
                    "\"transferId\":\""
                        + transferId
                        + "\",\"transferId\":\""
                        + transferId
                        + "\"")));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeRequest(request + "{}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeTicket(
                "{\"transferId\":\""
                    + transferId
                    + "\",\"state\":\"READY\",\"uploadId\":\""
                    + uploadId
                    + "\",\"message\":\"not allowed\"}"));
  }

  /** 数值、digest、UUID 与 Instant 必须使用协议规定的规范表示。 */
  @Test
  void rejectsNonCanonicalFieldValues() {
    UUID transferId = UUID.randomUUID();
    String prefix =
        "{\"transferId\":\"" + transferId + "\",\"mediaType\":\"image/png\",\"name\":null,";
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeRequest(prefix + "\"size\":-1,\"sha256\":\"" + SHA256 + "\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeRequest(
                prefix + "\"size\":1,\"sha256\":\"" + SHA256.toUpperCase() + "\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeCommit(
                "{\"transferId\":\""
                    + transferId.toString().toUpperCase()
                    + "\",\"uploadId\":\""
                    + UUID.randomUUID()
                    + "\"}"));

    UUID uploadId = UUID.randomUUID();
    String pending =
        "{\"transferId\":\""
            + transferId
            + "\",\"state\":\"PENDING\",\"uploadId\":\""
            + uploadId
            + "\",\"presignedPut\":{\"method\":\"PUT\",\"url\":\"https://example.test\","
            + "\"headers\":{},\"expiresAt\":\"2026-07-28T10:01:00.000Z\"}}";
    assertThrows(DaemonProtocolException.class, () -> codec.decodeTicket(pending));
  }

  /** 控制 payload 的原始预算与形状：空白、超长与非对象在任何字段解析前拒绝。 */
  @Test
  void rejectsBlankOversizedAndNonObjectPayloads() {
    for (String blank : List.of("", "   ")) {
      assertThrows(DaemonProtocolException.class, () -> codec.decodeRequest(blank));
      assertThrows(DaemonProtocolException.class, () -> codec.decodeCommit(blank));
      assertThrows(DaemonProtocolException.class, () -> codec.decodeTicket(blank));
      assertThrows(DaemonProtocolException.class, () -> codec.transferIdOf(blank));
    }
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.transferIdOf("x".repeat(DaemonResourceTransferCodec.MAX_PAYLOAD_CHARS + 1)));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeRequest("[]"));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeTicket("not-json"));
  }

  /** 显式文件名在 REQUEST 往返中保留；非字符串、空白名与非整数尺寸被拒绝。 */
  @Test
  void roundTripsNamedRequestAndRejectsInvalidNameOrSize() {
    UploadRequest named =
        new UploadRequest(UUID.randomUUID(), "image/png", "diagram.png", 7L, SHA256);
    assertEquals(named, codec.decodeRequest(codec.encodeRequest(named)));
    String prefix = "{\"transferId\":\"" + named.transferId() + "\",\"mediaType\":\"image/png\",";
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeRequest(prefix + "\"name\":123,\"size\":7,\"sha256\":\"" + SHA256 + "\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeRequest(
                prefix + "\"name\":\" \",\"size\":7,\"sha256\":\"" + SHA256 + "\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeRequest(
                prefix + "\"name\":null,\"size\":1.5,\"sha256\":\"" + SHA256 + "\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeRequest(
                "{\"transferId\":\""
                    + named.transferId()
                    + "\",\"mediaType\":\"\",\"name\":null,\"size\":1,\"sha256\":\""
                    + SHA256
                    + "\"}"));
  }

  /** ticket 状态机、presignedPut 形状与规范 Instant 的严格拒绝路径。 */
  @Test
  void rejectsInvalidTicketShape() {
    UUID transferId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    assertThrows(
        DaemonProtocolException.class, () -> codec.decodeTicket(ticket(transferId, "BOGUS", "")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeTicket(
                ticket(
                    transferId,
                    "PENDING",
                    ",\"uploadId\":\"" + uploadId + "\",\"presignedPut\":[]")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeTicket(
                pending(
                    transferId,
                    uploadId,
                    "{\"method\":\"PUT\",\"url\":\"https://x\",\"headers\":[],\"expiresAt\":\"2026-07-28T10:01:00Z\"}")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeTicket(
                pending(
                    transferId,
                    uploadId,
                    "{\"method\":\"PUT\",\"url\":\"https://x\",\"headers\":{\"k\":1},\"expiresAt\":\"2026-07-28T10:01:00Z\"}")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeTicket(
                pending(
                    transferId,
                    uploadId,
                    "{\"method\":\"PUT\",\"url\":\"https://x\",\"headers\":{},\"expiresAt\":\"nope\"}")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeTicket(
                pending(
                    transferId,
                    uploadId,
                    "{\"method\":\"POST\",\"url\":\"https://x\",\"headers\":{},\"expiresAt\":\"2026-07-28T10:01:00Z\"}")));
  }

  /** record 构造器的字段校验是 wire 之外的第一道门。 */
  @Test
  void recordConstructorsRejectInvalidFields() {
    UUID transferId = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class, () -> new UploadRequest(transferId, " ", null, 1L, SHA256));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UploadRequest(transferId, "image/png", " ", 1L, SHA256));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UploadRequest(transferId, "image/png", null, -1L, SHA256));

    DaemonPresignedPut put =
        new DaemonPresignedPut("PUT", "https://x", Map.of(), Instant.parse("2026-07-28T10:01:00Z"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UploadTicket(transferId, TicketState.PENDING, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UploadTicket(transferId, TicketState.READY, null, put, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UploadTicket(transferId, TicketState.FAILED, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new UploadTicket(
                transferId,
                TicketState.FAILED,
                null,
                null,
                "x".repeat(DaemonResourceTransferCodec.MAX_FAILURE_MESSAGE_CHARS + 1)));
  }

  private static String ticket(UUID transferId, String state, String extra) {
    return "{\"transferId\":\"" + transferId + "\",\"state\":\"" + state + "\"" + extra + "}";
  }

  private static String pending(UUID transferId, UUID uploadId, String presignedPut) {
    return "{\"transferId\":\""
        + transferId
        + "\",\"state\":\"PENDING\",\"uploadId\":\""
        + uploadId
        + "\",\"presignedPut\":"
        + presignedPut
        + "}";
  }
}

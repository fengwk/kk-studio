package fun.fengwk.kkstudio.harness.environment.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Claim;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Input;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Keepalive;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Resize;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Takeover;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.Attached;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.Exited;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.OpAck;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.ViewUpdate;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.WriterChanged;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Consumer;

/** TerminalControlCodec 的 canonical roundtrip、fixture 与严格负向回归。 */
class TerminalControlCodecTest {

  private static final Class<TerminalControlCodec.TerminalControlException> INVALID =
      TerminalControlCodec.TerminalControlException.class;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TerminalControlCodec CODEC = new TerminalControlCodec();

  @Test
  void everyCommandRoundtripsCanonically() {
    for (TerminalCommand command : TerminalControlSamples.commands()) {
      String json = CODEC.encodeCommand(command);
      assertEquals(command, CODEC.decodeCommand(json));
      // 编码是 canonical 的：解码再编码得到逐字节相同的文本。
      assertEquals(json, CODEC.encodeCommand(CODEC.decodeCommand(json)));
    }
  }

  @Test
  void everyEventRoundtripsCanonically() {
    for (TerminalEvent event : TerminalControlSamples.events()) {
      String json = CODEC.encodeEvent(event);
      assertEquals(event, CODEC.decodeEvent(json));
      assertEquals(json, CODEC.encodeEvent(CODEC.decodeEvent(json)));
    }
  }

  @Test
  void requestAndResponseRoundtripCanonically() {
    TerminalRequest request = TerminalControlSamples.request();
    String requestJson = CODEC.encodeRequest(request);
    assertEquals(request, CODEC.decodeRequest(requestJson));
    assertEquals(requestJson, CODEC.encodeRequest(CODEC.decodeRequest(requestJson)));

    TerminalResponse response = TerminalControlSamples.response();
    String responseJson = CODEC.encodeResponse(response);
    assertEquals(response, CODEC.decodeResponse(responseJson));
    assertEquals(responseJson, CODEC.encodeResponse(CODEC.decodeResponse(responseJson)));
  }

  @Test
  void openCommandMatchesSharedFixture() {
    String fixture = fixture("control-command-open.json");
    TerminalCommand decoded = CODEC.decodeCommand(fixture);
    assertEquals(TerminalControlSamples.openCommand(), decoded);
    assertEquals(fixture, CODEC.encodeCommand(decoded));
  }

  @Test
  void errorEventMatchesSharedFixture() {
    String fixture = fixture("control-event-error.json");
    TerminalEvent decoded = CODEC.decodeEvent(fixture);
    assertEquals(TerminalControlSamples.errorEvent(null), decoded);
    assertEquals(fixture, CODEC.encodeEvent(decoded));
  }

  @Test
  void requestMatchesSharedFixture() {
    String fixture = fixture("control-request-input.json");
    TerminalRequest decoded = CODEC.decodeRequest(fixture);
    assertEquals(TerminalControlSamples.request(), decoded);
    assertEquals(fixture, CODEC.encodeRequest(decoded));
  }

  @Test
  void rejectsMalformedTopLevelInput() {
    String valid = CODEC.encodeCommand(TerminalControlSamples.openCommand());
    assertThrows(INVALID, () -> CODEC.decodeCommand("[]"));
    assertThrows(INVALID, () -> CODEC.decodeCommand("null"));
    assertThrows(INVALID, () -> CODEC.decodeCommand("not-json"));
    assertThrows(INVALID, () -> CODEC.decodeCommand(null));
    // 尾随 text。
    assertThrows(INVALID, () -> CODEC.decodeCommand(valid + "{}"));
    // 重复 key。
    assertThrows(
        INVALID,
        () -> CODEC.decodeCommand(valid.replace("\"version\":1", "\"version\":1,\"version\":1")));
    // 未知与缺失字段。
    assertThrows(INVALID, () -> CODEC.decodeCommand(withField(valid, "extra", number(1))));
    assertThrows(INVALID, () -> CODEC.decodeCommand(withoutField(valid, "viewerId")));
  }

  @Test
  void rejectsUnknownTypeAndVersion() {
    String valid = CODEC.encodeCommand(TerminalControlSamples.openCommand());
    assertThrows(INVALID, () -> CODEC.decodeCommand(withField(valid, "type", text("NOPE"))));
    assertThrows(INVALID, () -> CODEC.decodeCommand(withField(valid, "version", number(2))));
    assertThrows(INVALID, () -> CODEC.decodeCommand(withField(valid, "version", text("1"))));
  }

  @Test
  void rejectsWrongNullAndTypes() {
    String valid = CODEC.encodeCommand(TerminalControlSamples.openCommand());
    assertThrows(
        INVALID, () -> CODEC.decodeCommand(withField(valid, "environmentId", MAPPER.nullNode())));
    assertThrows(INVALID, () -> CODEC.decodeCommand(withField(valid, "type", MAPPER.nullNode())));
    assertThrows(INVALID, () -> CODEC.decodeCommand(withField(valid, "payload", text("x"))));
    assertThrows(
        INVALID,
        () ->
            CODEC.decodeCommand(
                mutatePayload(valid, payload -> payload.put("expectedExited", "x"))));
  }

  @Test
  void rejectsNonCanonicalUuid() {
    String valid = CODEC.encodeCommand(TerminalControlSamples.openCommand());
    String canonical = TerminalControlSamples.DAEMON.toString();
    String upper = canonical.toUpperCase();
    assertThrows(INVALID, () -> CODEC.decodeCommand(valid.replace(canonical, upper)));
  }

  @Test
  void rejectsIntegerViolations() {
    String input = CODEC.encodeCommand(TerminalControlSamples.command(inputCommand()));
    assertThrows(INVALID, () -> CODEC.decodeCommand(mutatePayload(input, p -> p.put("seq", 1.5))));
    assertThrows(INVALID, () -> CODEC.decodeCommand(mutatePayload(input, p -> p.put("seq", 0))));
    assertThrows(
        INVALID,
        () ->
            CODEC.decodeCommand(
                mutatePayload(input, p -> p.put("seq", TerminalLimits.MAX_SAFE_INTEGER + 1))));
    assertThrows(INVALID, () -> CODEC.decodeCommand(mutatePayload(input, p -> p.put("seq", "1"))));
    assertThrows(
        INVALID,
        () ->
            CODEC.decodeCommand(
                mutatePayload(
                    input,
                    p ->
                        p.set(
                            "seq",
                            MAPPER
                                .getNodeFactory()
                                .numberNode(new BigInteger("123456789012345678901234567890"))))));
  }

  @Test
  void rejectsNonCanonicalBase64AndInputBounds() {
    String input = CODEC.encodeCommand(TerminalControlSamples.command(inputCommand()));
    assertThrows(
        INVALID, () -> CODEC.decodeCommand(mutatePayload(input, p -> p.put("bytes", "aGVsbG8"))));
    assertThrows(
        INVALID, () -> CODEC.decodeCommand(mutatePayload(input, p -> p.put("bytes", "aGVs bG8="))));
    assertThrows(
        INVALID, () -> CODEC.decodeCommand(mutatePayload(input, p -> p.put("bytes", "@@@@"))));
    assertThrows(INVALID, () -> CODEC.decodeCommand(mutatePayload(input, p -> p.put("bytes", ""))));
    // 文本长度下界：不足 4 字符即拒绝，不解码。
    assertThrows(
        INVALID, () -> CODEC.decodeCommand(mutatePayload(input, p -> p.put("bytes", "AAA"))));
    byte[] tooLarge = new byte[TerminalCommand.MAX_INPUT_BYTES + 1];
    String encodedTooLarge = Base64.getEncoder().encodeToString(tooLarge);
    assertThrows(
        INVALID,
        () -> CODEC.decodeCommand(mutatePayload(input, p -> p.put("bytes", encodedTooLarge))));
    // 文本长度上界：超过 4096 字节所能编码的 5464 字符即拒绝，避免先解码超大字符串。
    byte[] overBoundary = new byte[TerminalCommand.MAX_INPUT_BYTES + 3];
    String encodedOverBoundary = Base64.getEncoder().encodeToString(overBoundary);
    assertTrue(encodedOverBoundary.length() > 5464);
    assertThrows(
        INVALID,
        () -> CODEC.decodeCommand(mutatePayload(input, p -> p.put("bytes", encodedOverBoundary))));
  }

  @Test
  void inputAtMaxBytesRoundtrips() {
    byte[] bytes = new byte[TerminalCommand.MAX_INPUT_BYTES];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = (byte) index;
    }
    TerminalCommand command =
        TerminalControlSamples.command(
            new Input(
                TerminalControlSamples.identity(),
                TerminalControlSamples.STREAM,
                TerminalControlSamples.grant(),
                1L,
                1L,
                bytes));
    TerminalCommand decoded = CODEC.decodeCommand(CODEC.encodeCommand(command));
    assertEquals(command, decoded);
    assertTrue(Arrays.equals(bytes, ((Input) decoded.payload()).bytes()));
  }

  @Test
  void rejectsMissingNullableAndNestedFields() {
    String takeover =
        CODEC.encodeCommand(
            TerminalControlSamples.command(
                new Takeover(
                    TerminalControlSamples.identity(), TerminalControlSamples.STREAM, null)));
    assertThrows(
        INVALID,
        () -> CODEC.decodeCommand(mutatePayload(takeover, p -> p.remove("expectedWriterEpoch"))));

    String keepalive =
        CODEC.encodeCommand(
            TerminalControlSamples.command(
                new Keepalive(
                    TerminalControlSamples.identity(), TerminalControlSamples.STREAM, null)));
    assertThrows(
        INVALID, () -> CODEC.decodeCommand(mutatePayload(keepalive, p -> p.remove("grant"))));

    String claim =
        CODEC.encodeCommand(
            TerminalControlSamples.command(
                new Claim(
                    TerminalControlSamples.identity(),
                    TerminalControlSamples.STREAM,
                    TerminalControlSamples.recovery())));
    assertThrows(
        INVALID, () -> CODEC.decodeCommand(mutatePayload(claim, p -> recovery(p).remove("seq"))));
    assertThrows(
        INVALID, () -> CODEC.decodeCommand(mutatePayload(claim, p -> recovery(p).put("zzz", 1))));
    assertThrows(
        INVALID, () -> CODEC.decodeCommand(mutatePayload(claim, p -> identity(p).put("extra", 1))));
  }

  @Test
  void rejectsRecoveryInconsistencies() {
    String claim =
        CODEC.encodeCommand(
            TerminalControlSamples.command(
                new Claim(
                    TerminalControlSamples.identity(),
                    TerminalControlSamples.STREAM,
                    TerminalControlSamples.recovery())));
    assertThrows(
        INVALID,
        () -> CODEC.decodeCommand(mutatePayload(claim, p -> recovery(p).putNull("digest"))));
    assertThrows(
        INVALID, () -> CODEC.decodeCommand(mutatePayload(claim, p -> recovery(p).put("seq", 0))));
    assertThrows(
        INVALID,
        () -> CODEC.decodeCommand(mutatePayload(claim, p -> recovery(p).putNull("previous"))));
  }

  @Test
  void rejectsResizeOutOfRange() {
    String resize =
        CODEC.encodeCommand(
            TerminalControlSamples.command(
                new Resize(
                    TerminalControlSamples.identity(),
                    TerminalControlSamples.STREAM,
                    TerminalControlSamples.grant(),
                    1L,
                    80,
                    24)));
    assertThrows(
        INVALID, () -> CODEC.decodeCommand(mutatePayload(resize, p -> p.put("cols", 400))));
    assertThrows(INVALID, () -> CODEC.decodeCommand(mutatePayload(resize, p -> p.put("rows", 1))));
  }

  @Test
  void rejectsAttachedStatusInconsistencies() {
    String running =
        CODEC.encodeEvent(
            TerminalControlSamples.event(
                new Attached(
                    TerminalControlSamples.STREAM,
                    "/bin/bash",
                    TerminalStatus.RUNNING,
                    null,
                    1L,
                    TerminalControlSamples.writerActive())));
    assertThrows(
        INVALID, () -> CODEC.decodeEvent(mutatePayload(running, p -> p.put("exitCode", 0))));

    String exited =
        CODEC.encodeEvent(
            TerminalControlSamples.event(
                new Attached(
                    TerminalControlSamples.STREAM,
                    "/bin/sh",
                    TerminalStatus.EXITED,
                    0,
                    2L,
                    TerminalControlSamples.writerFrozen())));
    assertThrows(
        INVALID, () -> CODEC.decodeEvent(mutatePayload(exited, p -> p.putNull("exitCode"))));
    assertThrows(
        INVALID,
        () -> CODEC.decodeEvent(mutatePayload(exited, p -> p.put("inputModeRevision", 0))));
  }

  @Test
  void rejectsExitedAndIdentityInconsistencies() {
    String exited =
        CODEC.encodeEvent(TerminalControlSamples.event(new Exited(TerminalStatus.EXITED, 0)));
    assertThrows(
        INVALID, () -> CODEC.decodeEvent(mutatePayload(exited, p -> p.put("status", "RUNNING"))));
    assertThrows(
        INVALID, () -> CODEC.decodeEvent(mutatePayload(exited, p -> p.put("status", "NOPE"))));
    // 非 ERROR 事件不允许 null identity。
    assertThrows(
        INVALID, () -> CODEC.decodeEvent(withField(exited, "identity", MAPPER.nullNode())));
  }

  @Test
  void rejectsViewUpdateIdentityMismatch() {
    String view =
        CODEC.encodeEvent(
            TerminalControlSamples.event(new ViewUpdate(TerminalViewSamples.reset())));
    assertThrows(
        INVALID,
        () ->
            CODEC.decodeEvent(
                mutatePayload(
                    view,
                    p ->
                        ((ObjectNode) p.get("update"))
                            .put("terminalId", TerminalControlSamples.APP_NODE.toString()))));
    assertThrows(INVALID, () -> CODEC.decodeEvent(mutatePayload(view, p -> p.put("update", "x"))));
  }

  @Test
  void rejectsOpAckInconsistencies() {
    String pending =
        CODEC.encodeEvent(
            TerminalControlSamples.event(
                new OpAck(
                    TerminalControlSamples.EPOCH,
                    AdmissionResult.pending(1L, TerminalControlSamples.digest()),
                    null)));
    assertThrows(
        INVALID,
        () -> CODEC.decodeEvent(mutatePayload(pending, p -> result(p).put("kind", "ACCEPTED"))));
    assertThrows(
        INVALID, () -> CODEC.decodeEvent(mutatePayload(pending, p -> result(p).put("seq", 0))));
    assertThrows(
        INVALID,
        () ->
            CODEC.decodeEvent(
                mutatePayload(
                    pending, p -> result(p).put("seq", TerminalLimits.MAX_SAFE_INTEGER + 1))));
    assertThrows(
        INVALID, () -> CODEC.decodeEvent(mutatePayload(pending, p -> result(p).putNull("digest"))));
    assertThrows(
        INVALID, () -> CODEC.decodeEvent(mutatePayload(pending, p -> p.put("code", "NOPE"))));

    String confirmed =
        CODEC.encodeEvent(
            TerminalControlSamples.event(
                new OpAck(
                    TerminalControlSamples.EPOCH,
                    AdmissionResult.confirmed(
                        OperationOutcome.WRITTEN, 3L, TerminalControlSamples.digest()),
                    null)));
    assertThrows(
        INVALID,
        () -> CODEC.decodeEvent(mutatePayload(confirmed, p -> result(p).putNull("outcome"))));
  }

  @Test
  void rejectsWriterStateInconsistencies() {
    String attached =
        CODEC.encodeEvent(
            TerminalControlSamples.event(
                new Attached(
                    TerminalControlSamples.STREAM,
                    "/bin/bash",
                    TerminalStatus.RUNNING,
                    null,
                    1L,
                    TerminalControlSamples.writerActive())));
    assertThrows(
        INVALID,
        () -> CODEC.decodeEvent(mutatePayload(attached, p -> writer(p).put("lastWrittenSeq", 2))));
    assertThrows(
        INVALID,
        () ->
            CODEC.decodeEvent(
                mutatePayload(attached, p -> writer(p).putNull("lastWrittenDigest"))));
    assertThrows(
        INVALID,
        () -> CODEC.decodeEvent(mutatePayload(attached, p -> writer(p).put("frozen", true))));
    assertThrows(
        INVALID,
        () -> CODEC.decodeEvent(mutatePayload(attached, p -> writer(p).remove("pendingSeq"))));
  }

  @Test
  void rejectsControlResultInconsistencies() {
    String changed =
        CODEC.encodeEvent(
            TerminalControlSamples.event(
                new WriterChanged(
                    TerminalControlSamples.writerActive(),
                    ControlResult.granted(
                        TerminalControlSamples.grant(), OperationOutcome.WRITTEN))));
    assertThrows(
        INVALID, () -> CODEC.decodeEvent(mutatePayload(changed, p -> result(p).putNull("grant"))));
    assertThrows(
        INVALID,
        () -> CODEC.decodeEvent(mutatePayload(changed, p -> result(p).put("status", "REJECTED"))));
    assertThrows(
        INVALID,
        () -> CODEC.decodeEvent(mutatePayload(changed, p -> result(p).put("recovered", "NOPE"))));
  }

  @Test
  void rejectsMalformedRequestAndResponse() {
    String request = CODEC.encodeRequest(TerminalControlSamples.request());
    assertThrows(INVALID, () -> CODEC.decodeRequest(withField(request, "extra", number(1))));
    assertThrows(INVALID, () -> CODEC.decodeRequest(withoutField(request, "command")));
    assertThrows(
        INVALID,
        () -> CODEC.decodeRequest(mutateRoot(request, r -> route(r).put("connectionId", ""))));
    assertThrows(
        INVALID,
        () ->
            CODEC.decodeRequest(
                mutateRoot(
                    request,
                    r ->
                        route(r)
                            .put(
                                "connectionId",
                                "c".repeat(TerminalRoute.MAX_CONNECTION_ID_LENGTH + 1)))));
    assertThrows(
        INVALID,
        () ->
            CODEC.decodeRequest(
                mutateRoot(request, r -> route(r).put("connectionId", "a\u0000b"))));
    assertThrows(
        INVALID,
        () -> CODEC.decodeRequest(mutateRoot(request, r -> route(r).put("appNodeId", "x"))));
    assertThrows(INVALID, () -> CODEC.decodeRequest(request.replace("\"route\"", "\"routeX\"")));

    String response = CODEC.encodeResponse(TerminalControlSamples.response());
    assertThrows(INVALID, () -> CODEC.decodeResponse(withField(response, "extra", number(1))));
    assertThrows(INVALID, () -> CODEC.decodeResponse(withoutField(response, "route")));
    assertThrows(INVALID, () -> CODEC.decodeResponse(withoutField(response, "event")));
  }

  private static Input inputCommand() {
    return new Input(
        TerminalControlSamples.identity(),
        TerminalControlSamples.STREAM,
        TerminalControlSamples.grant(),
        1L,
        1L,
        "ls\n".getBytes(StandardCharsets.UTF_8));
  }

  private static ObjectNode parse(String json) {
    try {
      return (ObjectNode) MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String withField(String json, String field, JsonNode value) {
    ObjectNode root = parse(json);
    root.set(field, value);
    return root.toString();
  }

  private static String withoutField(String json, String field) {
    ObjectNode root = parse(json);
    root.remove(field);
    return root.toString();
  }

  private static String mutateRoot(String json, Consumer<ObjectNode> mutation) {
    ObjectNode root = parse(json);
    mutation.accept(root);
    return root.toString();
  }

  private static String mutatePayload(String json, Consumer<ObjectNode> mutation) {
    ObjectNode root = parse(json);
    mutation.accept((ObjectNode) root.get("payload"));
    return root.toString();
  }

  private static ObjectNode identity(ObjectNode payload) {
    return (ObjectNode) payload.get("identity");
  }

  private static ObjectNode recovery(ObjectNode payload) {
    return (ObjectNode) payload.get("recovery");
  }

  private static ObjectNode result(ObjectNode payload) {
    return (ObjectNode) payload.get("result");
  }

  private static ObjectNode writer(ObjectNode payload) {
    return (ObjectNode) payload.get("writer");
  }

  private static ObjectNode route(ObjectNode root) {
    return (ObjectNode) root.get("route");
  }

  private static JsonNode number(long value) {
    return MAPPER.getNodeFactory().numberNode(value);
  }

  private static JsonNode text(String value) {
    return MAPPER.getNodeFactory().textNode(value);
  }

  private static String fixture(String name) {
    try (InputStream input = TerminalControlCodecTest.class.getResourceAsStream(name)) {
      assertTrue(input != null, name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}

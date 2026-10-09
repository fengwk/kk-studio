package fun.fengwk.kkstudio.harness.environment.terminal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Attach;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Input;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Resize;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.ViewApplied;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.Attached;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.ErrorPayload;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.Exited;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.OpAck;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.ViewUpdate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** 控制模型的不变式、深不可变与去敏回归；不依赖 codec。 */
class TerminalControlModelTest {

  private static final UUID TERMINAL = TerminalControlSamples.TERMINAL;
  private static final UUID STREAM = TerminalControlSamples.STREAM;

  @Test
  void inputBytesAreDefensivelyCopied() {
    byte[] source = "top-secret".getBytes(StandardCharsets.UTF_8);
    Input input =
        new Input(
            TerminalControlSamples.identity(),
            STREAM,
            TerminalControlSamples.grant(),
            1L,
            1L,
            source);
    source[0] = 'X';
    assertArrayEquals("top-secret".getBytes(StandardCharsets.UTF_8), input.bytes());

    byte[] copy = input.bytes();
    copy[0] = 'Y';
    assertArrayEquals("top-secret".getBytes(StandardCharsets.UTF_8), input.bytes());
  }

  @Test
  void secretsAndPayloadsAreRedactedFromToString() {
    WriterGrant grant = TerminalControlSamples.grant();
    assertFalse(grant.toString().contains(grant.token().toString()));
    assertFalse(TerminalControlSamples.digest().toString().contains("000102"));

    Input input =
        new Input(
            TerminalControlSamples.identity(),
            STREAM,
            grant,
            1L,
            1L,
            "top-secret-input".getBytes(StandardCharsets.UTF_8));
    assertFalse(input.toString().contains("top-secret-input"));

    Attached attached =
        new Attached(
            STREAM,
            "/usr/bin/top-secret-exec",
            TerminalStatus.RUNNING,
            null,
            1L,
            TerminalControlSamples.writerActive());
    assertFalse(attached.toString().contains("top-secret-exec"));

    ViewUpdate viewUpdate = new ViewUpdate(TerminalViewSamples.reset());
    assertFalse(viewUpdate.toString().contains("TerminalViewUpdate["));
    assertFalse(viewUpdate.toString().contains("screenRows"));
  }

  @Test
  void codecErrorsDoNotEchoPayloadOrKeepCause() {
    String json =
        new TerminalControlCodec()
            .encodeEvent(
                TerminalControlSamples.event(
                    new Attached(
                        STREAM,
                        "top-secret-exec",
                        TerminalStatus.RUNNING,
                        null,
                        1L,
                        TerminalControlSamples.writerActive())));
    String corrupted = json.replace("\"exitCode\":null", "\"exitCode\":0");
    TerminalControlCodec.TerminalControlException error =
        assertThrows(
            TerminalControlCodec.TerminalControlException.class,
            () -> new TerminalControlCodec().decodeEvent(corrupted));
    assertFalse(error.getMessage().contains("top-secret-exec"));
    assertNull(error.getCause());
  }

  @Test
  void controlResultFactoriesEnforceInvariants() {
    WriterGrant grant = TerminalControlSamples.grant();
    assertEquals(ControlResult.Status.GRANTED, ControlResult.granted(grant, null).status());
    assertTrue(ControlResult.granted(grant, OperationOutcome.WRITTEN).isGranted());
    assertEquals(
        ControlResult.Status.REJECTED,
        ControlResult.rejected(ControlResult.RejectReason.FROZEN).status());

    assertThrows(
        IllegalArgumentException.class,
        () -> new ControlResult(ControlResult.Status.GRANTED, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ControlResult(ControlResult.Status.REJECTED, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ControlResult(ControlResult.Status.RENEWED, grant, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ControlResult(ControlResult.Status.RENEWED, null, OperationOutcome.WRITTEN, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ControlResult(
                ControlResult.Status.GRANTED, grant, null, ControlResult.RejectReason.FROZEN));
  }

  @Test
  void admissionResultFactoriesEnforceInvariants() {
    OperationDigest digest = TerminalControlSamples.digest();
    assertTrue(AdmissionResult.accepted(1L, digest).isAccepted());
    assertEquals(AdmissionResult.Kind.PENDING, AdmissionResult.pending(1L, digest).kind());
    assertEquals(
        OperationOutcome.WRITTEN,
        AdmissionResult.confirmed(OperationOutcome.WRITTEN, 1L, digest).outcome());
    assertEquals(
        AdmissionResult.Kind.REJECTED,
        AdmissionResult.rejected(AdmissionResult.RejectReason.INVALID, 1L, null).kind());
    assertEquals(
        AdmissionResult.RejectReason.FROZEN,
        AdmissionResult.rejected(AdmissionResult.RejectReason.FROZEN, 1L, digest).reason());

    assertThrows(
        IllegalArgumentException.class,
        () -> new AdmissionResult(AdmissionResult.Kind.PENDING, 1L, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AdmissionResult(AdmissionResult.Kind.CONFIRMED, 1L, digest, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AdmissionResult(AdmissionResult.Kind.REJECTED, 1L, digest, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AdmissionResult(
                AdmissionResult.Kind.REJECTED,
                1L,
                null,
                null,
                AdmissionResult.RejectReason.FROZEN));
  }

  @Test
  void writerStateValidatesWatermarks() {
    assertEquals(0L, TerminalControlSamples.writerIdle().lastResolvedSeq());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WriterState(
                TerminalControlSamples.EPOCH, 0L, null, 0L, null, null, 0L, null, true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WriterState(
                null,
                1L,
                null,
                1L,
                TerminalControlSamples.digest(),
                OperationOutcome.WRITTEN,
                0L,
                null,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WriterState(
                null, 0L, null, 0L, null, null, 0L, TerminalControlSamples.digest(), false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WriterState(
                null,
                2L,
                TerminalControlSamples.digest(),
                1L,
                TerminalControlSamples.digest(),
                OperationOutcome.WRITTEN,
                0L,
                null,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WriterState(
                null,
                0L,
                null,
                1L,
                TerminalControlSamples.digest(),
                OperationOutcome.WRITTEN,
                5L,
                TerminalControlSamples.otherDigest(),
                false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WriterState(null, -1L, null, 0L, null, null, 0L, null, false));
  }

  @Test
  void recoveryValidatesSeqAndDigest() {
    assertEquals(1L, TerminalControlSamples.recovery().seq());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Recovery(
                TerminalControlSamples.previousGrant(), 0L, TerminalControlSamples.digest()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Recovery(TerminalControlSamples.previousGrant(), 1L, null));
    assertThrows(NullPointerException.class, () -> new Recovery(null, 0L, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Recovery(TerminalControlSamples.previousGrant(), -1L, null));
  }

  @Test
  void terminalIdentityAndRouteValidateFields() {
    assertEquals(TERMINAL, TerminalControlSamples.identity().terminalId());
    assertThrows(NullPointerException.class, () -> new TerminalIdentity(null, TERMINAL));
    assertThrows(
        NullPointerException.class,
        () -> new TerminalIdentity(TerminalControlSamples.DAEMON, null));

    assertEquals(TerminalControlSamples.CONNECTION, TerminalControlSamples.route().connectionId());
    assertThrows(NullPointerException.class, () -> new TerminalRoute(null, "c1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalRoute(TerminalControlSamples.APP_NODE, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalRoute(TerminalControlSamples.APP_NODE, "a\u0000b"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalRoute(
                TerminalControlSamples.APP_NODE,
                "c".repeat(TerminalRoute.MAX_CONNECTION_ID_LENGTH + 1)));
  }

  @Test
  void commandPayloadsValidateFields() {
    assertThrows(NullPointerException.class, () -> new Attach(null));
    assertThrows(
        NullPointerException.class,
        () -> new Resize(null, STREAM, TerminalControlSamples.grant(), 1L, 80, 24));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Resize(
                TerminalControlSamples.identity(),
                STREAM,
                TerminalControlSamples.grant(),
                0L,
                80,
                24));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Resize(
                TerminalControlSamples.identity(),
                STREAM,
                TerminalControlSamples.grant(),
                1L,
                4,
                24));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ViewApplied(TerminalControlSamples.identity(), STREAM, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Input(
                TerminalControlSamples.identity(),
                STREAM,
                TerminalControlSamples.grant(),
                1L,
                1L,
                new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Input(
                TerminalControlSamples.identity(),
                STREAM,
                TerminalControlSamples.grant(),
                1L,
                1L,
                new byte[TerminalCommand.MAX_INPUT_BYTES + 1]));
    assertThrows(
        NullPointerException.class,
        () ->
            new TerminalCommand(
                null,
                TerminalControlSamples.ENVIRONMENT,
                TerminalControlSamples.VIEWER,
                new Attach(TerminalControlSamples.identity())));
  }

  @Test
  void eventPayloadsValidateVariantConsistency() {
    assertThrows(IllegalArgumentException.class, () -> new Exited(TerminalStatus.RUNNING, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Attached(
                STREAM,
                "/bin/bash",
                TerminalStatus.RUNNING,
                0,
                1L,
                TerminalControlSamples.writerIdle()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Attached(
                STREAM,
                "/bin/bash",
                TerminalStatus.EXITED,
                null,
                1L,
                TerminalControlSamples.writerFrozen()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Attached(
                STREAM, "", TerminalStatus.FAILED, null, 1L, TerminalControlSamples.writerIdle()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Attached(
                STREAM,
                "a".repeat(TerminalEvent.MAX_EXECUTABLE_UTF8_BYTES + 1),
                TerminalStatus.FAILED,
                null,
                1L,
                TerminalControlSamples.writerIdle()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Attached(
                STREAM,
                "\ud800",
                TerminalStatus.FAILED,
                null,
                1L,
                TerminalControlSamples.writerIdle()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OpAck(
                TerminalControlSamples.EPOCH,
                AdmissionResult.accepted(1L, TerminalControlSamples.digest()),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OpAck(
                TerminalControlSamples.EPOCH,
                AdmissionResult.pending(0L, TerminalControlSamples.digest()),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalEvent(
                TerminalControlSamples.REQUEST,
                TerminalControlSamples.ENVIRONMENT,
                TerminalControlSamples.VIEWER,
                null,
                new Exited(TerminalStatus.EXITED, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalEvent(
                TerminalControlSamples.REQUEST,
                TerminalControlSamples.ENVIRONMENT,
                TerminalControlSamples.VIEWER,
                new TerminalIdentity(
                    TerminalControlSamples.DAEMON, TerminalControlSamples.APP_NODE),
                new ViewUpdate(TerminalViewSamples.reset())));
    assertThrows(
        NullPointerException.class, () -> new ErrorPayload(null, ErrorDisposition.NOT_EXECUTED));
  }

  @Test
  void requestAndResponseRejectNulls() {
    assertThrows(
        NullPointerException.class,
        () -> new TerminalRequest(null, TerminalControlSamples.openCommand()));
    assertThrows(
        NullPointerException.class,
        () -> new TerminalRequest(TerminalControlSamples.route(), null));
    assertThrows(
        NullPointerException.class,
        () -> new TerminalResponse(null, TerminalControlSamples.response().event()));
    assertThrows(
        NullPointerException.class,
        () -> new TerminalResponse(TerminalControlSamples.route(), null));
  }

  @Test
  void inputBytesEqualityIsContentBased() {
    byte[] bytes = "ls\n".getBytes(StandardCharsets.UTF_8);
    Input first =
        new Input(
            TerminalControlSamples.identity(),
            STREAM,
            TerminalControlSamples.grant(),
            1L,
            1L,
            bytes);
    Input second =
        new Input(
            TerminalControlSamples.identity(),
            STREAM,
            TerminalControlSamples.grant(),
            1L,
            1L,
            Arrays.copyOf(bytes, bytes.length));
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }
}

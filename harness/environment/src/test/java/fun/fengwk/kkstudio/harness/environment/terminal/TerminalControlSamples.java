package fun.fengwk.kkstudio.harness.environment.terminal;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Attach;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Claim;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Close;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Detach;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Input;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Keepalive;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Open;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Release;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Resize;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.Takeover;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand.ViewApplied;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.Attached;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.ErrorPayload;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.Exited;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.OpAck;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.ViewUpdate;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent.WriterChanged;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** 控制协议测试共用的确定样本；与画面样本共享同一 terminal/stream 身份，便于 VIEW_UPDATE 一致性校验。 */
final class TerminalControlSamples {

  static final UUID DAEMON = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  static final UUID TERMINAL = TerminalViewSamples.TERMINAL_ID;
  static final UUID STREAM = TerminalViewSamples.STREAM_ID;
  static final UUID ENVIRONMENT = UUID.fromString("44444444-4444-4444-4444-444444444444");
  static final UUID VIEWER = UUID.fromString("55555555-5555-5555-5555-555555555555");
  static final UUID REQUEST = UUID.fromString("66666666-6666-6666-6666-666666666666");
  static final UUID APP_NODE = UUID.fromString("77777777-7777-7777-7777-777777777777");
  static final UUID EPOCH = UUID.fromString("88888888-8888-8888-8888-888888888888");
  static final UUID TOKEN = UUID.fromString("99999999-9999-9999-9999-999999999999");
  static final UUID PREVIOUS_EPOCH = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  static final UUID PREVIOUS_TOKEN = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  static final String CONNECTION = "conn-1";

  private TerminalControlSamples() {}

  static TerminalIdentity identity() {
    return new TerminalIdentity(DAEMON, TERMINAL);
  }

  static TerminalRoute route() {
    return new TerminalRoute(APP_NODE, CONNECTION);
  }

  static WriterGrant grant() {
    return new WriterGrant(EPOCH, TOKEN);
  }

  static WriterGrant previousGrant() {
    return new WriterGrant(PREVIOUS_EPOCH, PREVIOUS_TOKEN);
  }

  /** 32 字节摘要，十六进制为 {@code 000102...1f}。 */
  static OperationDigest digest() {
    byte[] value = new byte[OperationDigest.LENGTH];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) index;
    }
    return OperationDigest.of(value);
  }

  static OperationDigest otherDigest() {
    byte[] value = new byte[OperationDigest.LENGTH];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (0xff - index);
    }
    return OperationDigest.of(value);
  }

  static Recovery recovery() {
    return new Recovery(previousGrant(), 1L, digest());
  }

  static WriterState writerIdle() {
    return new WriterState(null, 0L, null, 0L, null, null, 0L, null, false);
  }

  static WriterState writerActive() {
    return new WriterState(
        EPOCH, 1L, digest(), 1L, digest(), OperationOutcome.WRITTEN, 0L, null, false);
  }

  static WriterState writerPending() {
    return new WriterState(
        EPOCH, 1L, digest(), 1L, digest(), OperationOutcome.WRITTEN, 2L, otherDigest(), false);
  }

  static WriterState writerFrozen() {
    return new WriterState(
        null, 0L, null, 1L, digest(), OperationOutcome.OUTCOME_UNKNOWN, 0L, null, true);
  }

  static TerminalCommand openCommand() {
    return command(new Open(identity()));
  }

  static TerminalCommand command(TerminalCommand.Payload payload) {
    return new TerminalCommand(REQUEST, ENVIRONMENT, VIEWER, payload);
  }

  /** 覆盖全部 11 个命令变体与 nullable 分叉。 */
  static List<TerminalCommand> commands() {
    byte[] input = "ls -la\n".getBytes(StandardCharsets.UTF_8);
    return List.of(
        command(new Open(identity())),
        command(new Open(null)),
        command(new Attach(identity())),
        command(new Detach(identity(), STREAM)),
        command(new Claim(identity(), STREAM, recovery())),
        command(new Claim(identity(), STREAM, null)),
        command(new Takeover(identity(), STREAM, EPOCH)),
        command(new Takeover(identity(), STREAM, null)),
        command(new Release(identity(), STREAM, grant())),
        command(new Input(identity(), STREAM, grant(), 1L, 7L, input)),
        command(new Resize(identity(), STREAM, grant(), 2L, 120, 40)),
        command(new ViewApplied(identity(), STREAM, 3L)),
        command(new Keepalive(identity(), STREAM, grant())),
        command(new Keepalive(identity(), STREAM, null)),
        command(new Close(identity(), EPOCH)),
        command(new Close(identity(), null)));
  }

  /** 覆盖全部 6 个事件变体、异步 requestId 与 ERROR 的可空 identity。 */
  static List<TerminalEvent> events() {
    return List.of(
        event(new Attached(STREAM, "/bin/bash", TerminalStatus.RUNNING, null, 1L, writerActive())),
        event(new Attached(STREAM, "/bin/sh", TerminalStatus.EXITED, 0, 2L, writerFrozen())),
        event(new Attached(STREAM, "/bin/zsh", TerminalStatus.FAILED, null, 3L, writerPending())),
        event(
            new WriterChanged(
                writerActive(), ControlResult.granted(grant(), OperationOutcome.WRITTEN))),
        event(new WriterChanged(writerFrozen(), null)),
        event(new OpAck(EPOCH, AdmissionResult.pending(1L, digest()), null)),
        event(
            new OpAck(
                EPOCH,
                AdmissionResult.rejected(AdmissionResult.RejectReason.FROZEN, 2L, digest()),
                ErrorCode.BUSY)),
        event(
            new OpAck(
                EPOCH, AdmissionResult.confirmed(OperationOutcome.WRITTEN, 3L, digest()), null)),
        event(new ViewUpdate(TerminalViewSamples.reset())),
        event(new Exited(TerminalStatus.EXITED, 0)),
        event(new Exited(TerminalStatus.FAILED, null)),
        asyncEvent(new Exited(TerminalStatus.FAILED, 127)),
        errorEvent(null),
        errorEvent(identity()));
  }

  static TerminalEvent event(TerminalEvent.Payload payload) {
    return new TerminalEvent(REQUEST, ENVIRONMENT, VIEWER, identity(), payload);
  }

  static TerminalEvent asyncEvent(TerminalEvent.Payload payload) {
    return new TerminalEvent(null, ENVIRONMENT, VIEWER, identity(), payload);
  }

  static TerminalEvent errorEvent(TerminalIdentity identity) {
    return new TerminalEvent(
        REQUEST,
        ENVIRONMENT,
        VIEWER,
        identity,
        new ErrorPayload(ErrorCode.RUNTIME_FAILED, ErrorDisposition.OUTCOME_UNKNOWN));
  }

  static TerminalRequest request() {
    byte[] input = "pwd\n".getBytes(StandardCharsets.UTF_8);
    return new TerminalRequest(
        route(), command(new Input(identity(), STREAM, grant(), 4L, 2L, input)));
  }

  static TerminalResponse response() {
    return new TerminalResponse(route(), event(new Exited(TerminalStatus.EXITED, 0)));
  }
}

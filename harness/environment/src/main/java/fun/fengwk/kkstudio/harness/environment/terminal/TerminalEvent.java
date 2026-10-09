package fun.fengwk.kkstudio.harness.environment.terminal;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

import java.util.Objects;
import java.util.UUID;

/**
 * transport-free 的终端控制事件：Daemon 到控制者的回执或异步通知。
 *
 * <p>根字段固定为 {@code requestId}/{@code environmentId}/{@code viewerId}/{@code identity}/{@code
 * type}/{@code payload}。env/viewer 不可空；{@code requestId} 为 {@code null} 表示非请求关联的异步通知，控制响应可携带原
 * requestId；{@code identity} 只在 {@link ErrorPayload} 事件里允许为 {@code null}。
 *
 * @param requestId 关联请求标识，异步通知时为 {@code null}
 * @param environmentId 环境 id
 * @param viewerId 目标浏览器页面
 * @param identity 终端身份，仅 ERROR 事件可为 {@code null}
 * @param payload 事件载荷
 */
public record TerminalEvent(
    UUID requestId, UUID environmentId, UUID viewerId, TerminalIdentity identity, Payload payload) {

  /** ATTACHED.executable 的 UTF-8 字节上限。 */
  public static final int MAX_EXECUTABLE_UTF8_BYTES = 4096;

  public TerminalEvent {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewerId, "viewerId");
    Objects.requireNonNull(payload, "payload");
    if (!(payload instanceof ErrorPayload) && identity == null) {
      throw new IllegalArgumentException("identity may be null only for ERROR");
    }
    if (payload instanceof ViewUpdate viewUpdate
        && !viewUpdate.update().terminalId().equals(identity.terminalId())) {
      throw new IllegalArgumentException("VIEW_UPDATE must match the event terminal identity");
    }
  }

  /** 事件类型；名称即 wire 上的 {@code type}。 */
  public enum Type {
    ATTACHED,
    WRITER_CHANGED,
    OP_ACK,
    VIEW_UPDATE,
    EXITED,
    ERROR
  }

  /** 事件载荷；每种事件一个不可变变体，字段与 wire 精确一致。 */
  public sealed interface Payload {

    /** 该载荷对应的 wire {@code type}。 */
    Type type();
  }

  /** ATTACHED：附着成功，返回流、程序、状态与 writer 状态。 */
  public record Attached(
      UUID streamId,
      String executable,
      TerminalStatus status,
      Integer exitCode,
      long inputModeRevision,
      WriterState writer)
      implements Payload {

    public Attached {
      Objects.requireNonNull(streamId, "streamId");
      Objects.requireNonNull(writer, "writer");
      executable = requireExecutable(executable);
      requireStatus(status, exitCode);
      requirePositive(inputModeRevision, "inputModeRevision");
    }

    @Override
    public Type type() {
      return Type.ATTACHED;
    }

    /** 不回显已解析 executable。 */
    @Override
    public String toString() {
      return "Attached[streamId="
          + streamId
          + ", status="
          + status
          + ", exitCode="
          + exitCode
          + ", inputModeRevision="
          + inputModeRevision
          + ", executable=<redacted>]";
    }
  }

  /** WRITER_CHANGED：writer 状态变化，可选携带控制结果。 */
  public record WriterChanged(WriterState writer, ControlResult result) implements Payload {

    public WriterChanged {
      Objects.requireNonNull(writer, "writer");
    }

    @Override
    public Type type() {
      return Type.WRITER_CHANGED;
    }
  }

  /** OP_ACK：一次 INPUT/RESIZE 的准入决议；绝不能把 ACCEPTED 当作 wire ACK。 */
  public record OpAck(UUID writerEpoch, AdmissionResult result, ErrorCode code) implements Payload {

    public OpAck {
      Objects.requireNonNull(writerEpoch, "writerEpoch");
      Objects.requireNonNull(result, "result");
      if (result.kind() == AdmissionResult.Kind.ACCEPTED) {
        throw new IllegalArgumentException("ACCEPTED is not a wire acknowledgement");
      }
      if (result.seq() < 1L || result.seq() > TerminalLimits.MAX_SAFE_INTEGER) {
        throw new IllegalArgumentException("result seq must be a positive JS safe integer");
      }
    }

    @Override
    public Type type() {
      return Type.OP_ACK;
    }
  }

  /** VIEW_UPDATE：完整画面更新，嵌入既有 {@link TerminalViewUpdate}。 */
  public record ViewUpdate(TerminalViewUpdate update) implements Payload {

    public ViewUpdate {
      Objects.requireNonNull(update, "update");
    }

    @Override
    public Type type() {
      return Type.VIEW_UPDATE;
    }

    /** 不回显画面内容。 */
    @Override
    public String toString() {
      return "ViewUpdate[terminalId="
          + update.terminalId()
          + ", streamId="
          + update.streamId()
          + ", version="
          + update.version()
          + ", update=<redacted>]";
    }
  }

  /** EXITED：终端已结束；不得声明 RUNNING。 */
  public record Exited(TerminalStatus status, Integer exitCode) implements Payload {

    public Exited {
      if (status == TerminalStatus.RUNNING) {
        throw new IllegalArgumentException("EXITED must not report RUNNING");
      }
      requireStatus(status, exitCode);
    }

    @Override
    public Type type() {
      return Type.EXITED;
    }
  }

  /** ERROR：固定错误码与确定性分类，不含自由错误文本。 */
  public record ErrorPayload(ErrorCode code, ErrorDisposition disposition) implements Payload {

    public ErrorPayload {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(disposition, "disposition");
    }

    @Override
    public Type type() {
      return Type.ERROR;
    }
  }

  /** 校验 status 与 exitCode 的存在性一致：RUNNING 无退出码，EXITED 必须有退出码，FAILED 可有可无。 */
  private static void requireStatus(TerminalStatus status, Integer exitCode) {
    Objects.requireNonNull(status, "status");
    if (status == TerminalStatus.RUNNING && exitCode != null) {
      throw new IllegalArgumentException("RUNNING must not declare an exitCode");
    }
    if (status == TerminalStatus.EXITED && exitCode == null) {
      throw new IllegalArgumentException("EXITED must declare an exitCode");
    }
  }

  /**
   * 校验 executable 为非空有效 UTF-16 文本且不超过 {@value TerminalEvent#MAX_EXECUTABLE_UTF8_BYTES} UTF-8 字节。
   */
  private static String requireExecutable(String executable) {
    if (executable == null || executable.isEmpty()) {
      throw new IllegalArgumentException("executable must not be empty");
    }
    if (ResourceRef.utf8Length(executable, "executable") > MAX_EXECUTABLE_UTF8_BYTES) {
      throw new IllegalArgumentException("executable is too long");
    }
    return executable;
  }

  private static void requirePositive(long value, String name) {
    if (value < 1L || value > TerminalLimits.MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException(name + " must be a positive JS safe integer");
    }
  }
}

package fun.fengwk.kkstudio.harness.environment.terminal;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * transport-free 的终端控制命令：一次控制者到 Daemon 的请求载荷。
 *
 * <p>根字段固定为 {@code requestId}/{@code environmentId}/{@code viewerId} 与一个 {@link Payload}；三个 UUID
 * 都不可空。命令不携带 route、owner 地址或 Daemon 注册凭据，这些由网络 adapter 单独验证。{@code payload} 的具体字段由 {@link Type}
 * 决定，没有第二份 DTO 或 enum 映射。
 *
 * @param requestId 请求标识，用于幂等重放
 * @param environmentId 目标环境
 * @param viewerId 发起控制的浏览器页面
 * @param payload 命令载荷
 */
public record TerminalCommand(UUID requestId, UUID environmentId, UUID viewerId, Payload payload) {

  /** 单次 INPUT 的字节数下限。 */
  public static final int MIN_INPUT_BYTES = 1;

  /** 单次 INPUT 的字节数上限。 */
  public static final int MAX_INPUT_BYTES = 4096;

  public TerminalCommand {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewerId, "viewerId");
    Objects.requireNonNull(payload, "payload");
  }

  /** 命令类型；名称即 wire 上的 {@code type}。 */
  public enum Type {
    OPEN,
    ATTACH,
    DETACH,
    CLAIM,
    TAKEOVER,
    RELEASE,
    INPUT,
    RESIZE,
    VIEW_APPLIED,
    KEEPALIVE,
    CLOSE
  }

  /** 命令载荷；每种命令一个不可变变体，字段与 wire 精确一致。 */
  public sealed interface Payload {

    /** 该载荷对应的 wire {@code type}。 */
    Type type();
  }

  /** OPEN：创建终端，可选声明一个期望已退出的终端身份。 */
  public record Open(TerminalIdentity expectedExited) implements Payload {

    @Override
    public Type type() {
      return Type.OPEN;
    }
  }

  /** ATTACH：附着到指定终端。 */
  public record Attach(TerminalIdentity identity) implements Payload {

    public Attach {
      Objects.requireNonNull(identity, "identity");
    }

    @Override
    public Type type() {
      return Type.ATTACH;
    }
  }

  /** DETACH：从指定终端的一个观察流分离。 */
  public record Detach(TerminalIdentity identity, UUID streamId) implements Payload {

    public Detach {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(streamId, "streamId");
    }

    @Override
    public Type type() {
      return Type.DETACH;
    }
  }

  /** CLAIM：申请控制权，可选携带跨连接恢复参数。 */
  public record Claim(TerminalIdentity identity, UUID streamId, Recovery recovery)
      implements Payload {

    public Claim {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(streamId, "streamId");
    }

    @Override
    public Type type() {
      return Type.CLAIM;
    }
  }

  /** TAKEOVER：以观察到的 writer epoch 作 CAS 夺取控制权。 */
  public record Takeover(TerminalIdentity identity, UUID streamId, UUID expectedWriterEpoch)
      implements Payload {

    public Takeover {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(streamId, "streamId");
    }

    @Override
    public Type type() {
      return Type.TAKEOVER;
    }
  }

  /** RELEASE：释放当前控制权。 */
  public record Release(TerminalIdentity identity, UUID streamId, WriterGrant grant)
      implements Payload {

    public Release {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(streamId, "streamId");
      Objects.requireNonNull(grant, "grant");
    }

    @Override
    public Type type() {
      return Type.RELEASE;
    }
  }

  /** INPUT：提交一次输入；{@code bytes} 独占不可变，1..{@value TerminalCommand#MAX_INPUT_BYTES} 字节。 */
  public record Input(
      TerminalIdentity identity,
      UUID streamId,
      WriterGrant grant,
      long seq,
      long inputModeRevision,
      byte[] bytes)
      implements Payload {

    public Input {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(streamId, "streamId");
      Objects.requireNonNull(grant, "grant");
      Objects.requireNonNull(bytes, "bytes");
      requirePositive(seq, "seq");
      requirePositive(inputModeRevision, "inputModeRevision");
      if (bytes.length < MIN_INPUT_BYTES || bytes.length > MAX_INPUT_BYTES) {
        throw new IllegalArgumentException("input length out of range");
      }
      bytes = bytes.clone();
    }

    /** 独占输入字节的防御性副本。 */
    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public Type type() {
      return Type.INPUT;
    }

    /** 字节按内容比较：独占 byte[] 的 record 默认按引用比较不足以表达 wire 等值。 */
    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      return other instanceof Input that
          && seq == that.seq
          && inputModeRevision == that.inputModeRevision
          && identity.equals(that.identity)
          && streamId.equals(that.streamId)
          && grant.equals(that.grant)
          && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(identity, streamId, grant, seq, inputModeRevision);
      return 31 * result + Arrays.hashCode(bytes);
    }

    /** 不回显输入字节。 */
    @Override
    public String toString() {
      return "Input[identity="
          + identity
          + ", streamId="
          + streamId
          + ", seq="
          + seq
          + ", inputModeRevision="
          + inputModeRevision
          + ", bytes=<redacted>]";
    }
  }

  /** RESIZE：提交一次终端尺寸变更。 */
  public record Resize(
      TerminalIdentity identity, UUID streamId, WriterGrant grant, long seq, int cols, int rows)
      implements Payload {

    public Resize {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(streamId, "streamId");
      Objects.requireNonNull(grant, "grant");
      requirePositive(seq, "seq");
      if (cols < TerminalLimits.MIN_COLUMNS || cols > TerminalLimits.MAX_COLUMNS) {
        throw new IllegalArgumentException("cols out of range");
      }
      if (rows < TerminalLimits.MIN_ROWS || rows > TerminalLimits.MAX_ROWS) {
        throw new IllegalArgumentException("rows out of range");
      }
    }

    @Override
    public Type type() {
      return Type.RESIZE;
    }
  }

  /** VIEW_APPLIED：确认某个画面版本已被浏览器应用。 */
  public record ViewApplied(TerminalIdentity identity, UUID streamId, long version)
      implements Payload {

    public ViewApplied {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(streamId, "streamId");
      requirePositive(version, "version");
    }

    @Override
    public Type type() {
      return Type.VIEW_APPLIED;
    }
  }

  /** KEEPALIVE：续租/心跳，可选携带当前授权；无授权时 grant 为 {@code null}。 */
  public record Keepalive(TerminalIdentity identity, UUID streamId, WriterGrant grant)
      implements Payload {

    public Keepalive {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(streamId, "streamId");
    }

    @Override
    public Type type() {
      return Type.KEEPALIVE;
    }
  }

  /** CLOSE：关闭终端，可选声明期望的 writer epoch。 */
  public record Close(TerminalIdentity identity, UUID expectedWriterEpoch) implements Payload {

    public Close {
      Objects.requireNonNull(identity, "identity");
    }

    @Override
    public Type type() {
      return Type.CLOSE;
    }
  }

  private static void requirePositive(long value, String name) {
    if (value < 1L || value > TerminalLimits.MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException(name + " must be a positive JS safe integer");
    }
  }
}

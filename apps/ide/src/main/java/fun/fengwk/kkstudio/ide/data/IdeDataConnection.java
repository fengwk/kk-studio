package fun.fengwk.kkstudio.ide.data;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.mux.MuxConnection;

import java.util.Objects;

/**
 * 已接受的单条 Environment 数据连接；物理适配器把完整 binary message 交给 {@link #receive(byte[])}。
 *
 * <p>代际由 {@link IdeDataConnectionRegistry} 在消费票据时唯一分配。物理断开必须调用 {@link
 * #fail(Throwable)}，使注册表只在仍是当前代际时清除 状态；迟到回调不能注销后来接受的新连接。关闭由注册表负责，以区分正常替换与物理失败。
 */
public final class IdeDataConnection {

  private final EnvironmentId environmentId;
  private final long generation;
  private final DaemonConnectionIdentity controlIdentity;
  private final MuxConnection mux;

  IdeDataConnection(
      EnvironmentId environmentId,
      long generation,
      DaemonConnectionIdentity controlIdentity,
      MuxConnection mux) {
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.generation = generation;
    this.controlIdentity = Objects.requireNonNull(controlIdentity, "controlIdentity");
    this.mux = Objects.requireNonNull(mux, "mux");
  }

  public EnvironmentId environmentId() {
    return environmentId;
  }

  /** 本条已接受数据连接的唯一代际。 */
  public long generation() {
    return generation;
  }

  /** 票据绑定的 READY 控制身份。 */
  public DaemonConnectionIdentity controlIdentity() {
    return controlIdentity;
  }

  public boolean isOpen() {
    return mux.isOpen();
  }

  /** 当前活动逻辑流数；用于 IDE 更新准入。 */
  public int activeStreamCount() {
    return mux.activeStreamCount();
  }

  /** 处理一条完整 wire 帧；必须在单线程内调用。 */
  public void receive(byte[] wireFrame) {
    mux.receive(wireFrame);
  }

  /** 物理传输断开或失败；幂等 fail-stop。 */
  public void fail(Throwable cause) {
    mux.fail(Objects.requireNonNull(cause, "cause"));
  }

  MuxConnection mux() {
    return mux;
  }

  /** 仅由注册表在已清除当前代际后调用。 */
  void close() {
    mux.close();
  }
}

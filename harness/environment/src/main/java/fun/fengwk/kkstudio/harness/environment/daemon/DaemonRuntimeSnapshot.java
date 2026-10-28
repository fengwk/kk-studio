package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;

/**
 * 一个控制连接代际内运行的完整 runtimeStatus 快照。
 *
 * <p>{@code revision} 是当前控制代际内独立递增的规范正十进制字符串；Server 只在接受更高 revision 时替换缓存，同 revision 内容不同属于协议错误。
 */
public record DaemonRuntimeSnapshot(long revision, DaemonRuntimeStatus status) {

  public DaemonRuntimeSnapshot {
    if (revision <= 0) {
      throw new IllegalArgumentException("revision must be a positive long");
    }
    status = Objects.requireNonNull(status, "status");
  }
}

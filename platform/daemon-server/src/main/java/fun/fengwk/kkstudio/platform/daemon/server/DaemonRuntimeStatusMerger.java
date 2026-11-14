package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;

/**
 * 单连接内的运行快照合并规则。
 *
 * <p>快照按 revision 单调推进：旧 revision 直接忽略，同 revision 必须完全等价，新 revision 才替换。整个连接只允许出现一个 daemonVersion，且
 * READY 之后必须与握手绑定的版本一致——版本变化意味着对端重启，应换连接而不是就地改版本。
 *
 * <p>本类不是线程安全的；调用方需持有会话状态锁。
 */
final class DaemonRuntimeStatusMerger {

  private DaemonRuntimeSnapshot current;

  /** 当前快照；尚未收到过任何快照时为 null。 */
  DaemonRuntimeSnapshot current() {
    return current;
  }

  /**
   * 合并一个快照。
   *
   * @param expectedDaemonVersion READY 之前为 null；READY 之后为握手绑定的版本
   * @return 状态内容实际变化时返回 true
   */
  boolean merge(DaemonRuntimeSnapshot snapshot, String expectedDaemonVersion) {
    if (expectedDaemonVersion != null
        && !snapshot.status().daemonVersion().equals(expectedDaemonVersion)) {
      throw new ControlProtocolException(
          "runtimeStatus.daemonVersion does not match the READY daemonVersion");
    }
    if (current != null
        && !snapshot.status().daemonVersion().equals(current.status().daemonVersion())) {
      throw new ControlProtocolException(
          "runtimeStatus.daemonVersion changed within one connection");
    }
    if (current == null) {
      current = snapshot;
      return true;
    }
    if (snapshot.revision() < current.revision()) {
      return false;
    }
    if (snapshot.revision() == current.revision()) {
      if (!snapshot.equals(current)) {
        throw new ControlProtocolException(
            "runtimeStatus revision " + snapshot.revision() + " changed without advancing");
      }
      return false;
    }
    boolean changed = !snapshot.status().equals(current.status());
    current = snapshot;
    return changed;
  }
}

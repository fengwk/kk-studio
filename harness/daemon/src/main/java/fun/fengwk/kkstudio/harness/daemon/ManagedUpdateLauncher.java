package fun.fengwk.kkstudio.harness.daemon;

/**
 * 受管更新器启动窄端口：把一份已验证的 {@link ManagedUpdateOutcome.Prepared} 交给独立于旧 Daemon 生命周期的更新器进程。
 *
 * <p>实现必须是短生命周期、OS 分离的：更新器在旧服务被停止后仍需存活，自行完成二进制替换与服务重启。daemon 运行时只负责启动并 观察启动是否成功，不等待、不驻留、不代替更新器做替换。
 */
public interface ManagedUpdateLauncher {

  /** 启动一次分离更新；返回 {@code false} 表示更新器未能进入运行（旧二进制保持不动）。 */
  boolean launch(ManagedUpdateOutcome.Prepared prepared);
}

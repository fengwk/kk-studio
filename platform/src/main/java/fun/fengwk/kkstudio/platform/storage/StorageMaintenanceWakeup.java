package fun.fengwk.kkstudio.platform.storage;

/** 本地存储维护线程的窄唤醒端口。调用必须快速、非阻塞且不执行 S3 I/O。 */
@FunctionalInterface
public interface StorageMaintenanceWakeup {

  void wake();
}

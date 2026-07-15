package fun.fengwk.kkstudio.harness.runtime.extension;

/** 接收进程内生命周期通知；失败由 publisher 隔离。 */
@FunctionalInterface
public interface HarnessLifecycleObserver {
  void observe(HarnessLifecycleObservation observation);
}

package fun.fengwk.kkstudio.harness.runtime.thread;

/** 事件触发的 Thread 激活端口：submit / permission / tool / subagent 完成后调用。 */
@FunctionalInterface
public interface ThreadKick {
  void kick(long threadId);
}

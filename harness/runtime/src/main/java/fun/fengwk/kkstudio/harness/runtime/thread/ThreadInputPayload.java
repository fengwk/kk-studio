package fun.fengwk.kkstudio.harness.runtime.thread;

/**
 * ThreadInput 的 typed command 负载抽象。
 *
 * <p>每种实现必须能标识所属的 {@link ThreadInputType}。
 */
public interface ThreadInputPayload {

  /** 返回该负载对应的 ThreadInput 类型。 */
  ThreadInputType type();
}

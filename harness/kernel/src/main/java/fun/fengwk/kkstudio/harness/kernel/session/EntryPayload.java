package fun.fengwk.kkstudio.harness.kernel.session;

/**
 * SessionEntry 的负载抽象。
 *
 * <p>每种实现必须能唯一标识自身所属的 {@link EntryType}，以便在追加 Entry 时做强制校验。
 */
public interface EntryPayload {

  /** 返回该负载对应的 Entry 类型。 */
  EntryType type();
}

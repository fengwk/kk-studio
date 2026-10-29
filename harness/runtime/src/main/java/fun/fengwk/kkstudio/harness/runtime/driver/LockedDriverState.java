package fun.fengwk.kkstudio.harness.runtime.driver;

import java.util.UUID;

/**
 * 事务内 Driver 状态的中立视图。
 *
 * <p>实现可以持有 Driver 私有状态（例如 Builtin 的 ModelInvocation），但 Runtime 只能通过本接口读取活动类别与中立投影，
 * 不得强转或读取实现字段。实例只在产生它的 store transaction 内有效；只有 {@link AgentDriver#lockState} 返回的状态可用于执行和变更，{@link
 * AgentDriver#readState} 返回的状态仅供读取。
 */
public interface LockedDriverState {

  DriverActivity activity();

  /** 本次 generation 冻结的 request head；无状态时为 {@code null}。 */
  UUID requestHeadEntryId();

  /** 已挂接的结果 Entry；尚未挂接时为 {@code null}。 */
  UUID resultEntryId();

  /** 中立诊断投影；无状态时为 {@code null}。 */
  GenerationProjection projection();

  /** Driver generation 的稳定身份；无状态时为 {@code null}。 */
  default UUID generationId() {
    return null;
  }

  /** 需要提交后 best-effort 取消的本地执行身份；无执行或已终止时为 {@code null}。Runtime 只把它当作不透明 UUID 传递。 */
  default UUID executionId() {
    return null;
  }

  /** head 是否恰为 request basis 且尚无结果。 */
  default boolean atBasis(UUID headEntryId) {
    return requestHeadEntryId() != null
        && requestHeadEntryId().equals(headEntryId)
        && resultEntryId() == null;
  }

  /** head 是否恰为已挂接的结果。 */
  default boolean resultAt(UUID headEntryId) {
    return resultEntryId() != null && resultEntryId().equals(headEntryId);
  }
}

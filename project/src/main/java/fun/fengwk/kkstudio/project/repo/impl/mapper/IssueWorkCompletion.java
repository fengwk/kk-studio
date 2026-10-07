package fun.fengwk.kkstudio.project.repo.impl.mapper;

/**
 * {@code completeWork} 单条 CTE 的实际写入结果：区分删除、释放与完全未写。
 *
 * <p>公共 {@code completeWork} 的 boolean 只表达「是否完成删除」；{@link #RELEASED} 同样返回 false，但它是真实的写事实（新 wake
 * 到达后释放租约并立即到期），需要发出到期提示；{@link #NONE} 表示围栏未匹配、没有写入，必须静默。
 */
public enum IssueWorkCompletion {
  /** 版本与租约均匹配，work 行已删除。 */
  DELETED,
  /** 版本已被新 wake 推进，已释放租约并把 due 提前到当前时刻。 */
  RELEASED,
  /** 租约或版本围栏未匹配，未执行任何写入。 */
  NONE
}

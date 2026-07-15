package fun.fengwk.kkstudio.harness.runtime.control;

/** 控制队列分类，决定持久化 policy 与消费时机。 */
public enum RunControlKind {
  /** 在当前 Turn 边界抢占式插入的 steer 输入。 */
  STEER,
  /** 在当前 Run 结束后排队进入下一 Run 的 follow-up 输入。 */
  FOLLOW_UP
}

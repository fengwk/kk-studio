package fun.fengwk.kkstudio.harness.runtime.history;

/** 一次 fork 的模式：只陈述历史来源形态，不代表任何执行控制或谱系。 */
public enum ForkMode {
  /** 同 Session 分支 fork：共享切点之前的不可变 Entry 前缀。 */
  BRANCH,

  /** 会话 fork：把切点处的有效上下文复制到新的 Session。 */
  SESSION
}

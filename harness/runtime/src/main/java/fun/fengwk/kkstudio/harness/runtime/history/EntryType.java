package fun.fengwk.kkstudio.harness.runtime.history;

/** Session Entry Tree 中 Entry 的最终语义类型。 */
public enum EntryType {
  /** Session 语义根，唯一且无 parent，携带初始 branch settings。 */
  ROOT,
  /** 一次 Model response turn 的完整 branch settings 快照与启动原因。 */
  TURN_START,
  /** 对话消息；具体结构由 payload 子类型决定。 */
  MESSAGE,
  /** 业务插件追加的透明 branch state 节点：不参与 turn grammar，默认不投影给 provider。 */
  CUSTOM,
  /** Model retry failure audit node：透明、不属于对话语义，也不关闭 Turn。 */
  MODEL_ATTEMPT_FAILURE,
  /** 由业务扩展注入的对话消息。 */
  CUSTOM_MESSAGE,
  /** Provider/Assistant-side 错误审计。 */
  ASSISTANT_ERROR,
  /** 用户主动 stop 的 assistant turn：仅保存安全 text/thinking，绝不包含 tool call。 */
  ASSISTANT_ABORTED,
  /** 自动压缩（compaction）turn 的摘要结果：仅出现在 COMPACTION turn 的 assistant result 位置。 */
  COMPACTION,
  /** 一次 Model response turn 的关闭结果与 continuation obligation。 */
  TURN_END;

  /** 是否为 Session Tree 的语义根。 */
  public boolean isRoot() {
    return this == ROOT;
  }
}

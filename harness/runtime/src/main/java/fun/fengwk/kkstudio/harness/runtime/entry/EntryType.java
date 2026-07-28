package fun.fengwk.kkstudio.harness.runtime.entry;

/**
 * Session Tree 中 Entry 的语义类型枚举。
 *
 * <p>新增枚举值需同步扩展持久化与运行时协调路径。
 */
public enum EntryType {
  /** Session 语义根，唯一且无 parent。 */
  ROOT,
  /** 完整、不可变的运行配置快照。 */
  RUNTIME_CONFIG,
  /** 对话消息；具体结构由 payload 子类型决定。 */
  MESSAGE,
  /** 由业务扩展注入的对话消息。 */
  CUSTOM_MESSAGE,
  /** Provider/Assistant-side 错误审计。 */
  ASSISTANT_ERROR,
  /**
   * 用户主动 stop 的 assistant turn：仅保存安全 text/thinking，绝不含 tool call；作为 ModelInvocationPlanner 的 debt
   * barrier。
   */
  ASSISTANT_ABORTED;

  /** 是否为 Session Tree 的语义根。 */
  public boolean isRoot() {
    return this == ROOT;
  }
}

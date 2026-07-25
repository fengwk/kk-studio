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
  /** Context 压缩或合并产物。 */
  COMPACTION,
  /** Provider/Assistant-side 错误审计。 */
  ASSISTANT_ERROR,
  /** 不投影到 Provider Context 的路径标签。 */
  LABEL,
  /** Branch 路径结束处的摘要。 */
  BRANCH_SUMMARY;

  /** 是否为 Session Tree 的语义根。 */
  public boolean isRoot() {
    return this == ROOT;
  }
}

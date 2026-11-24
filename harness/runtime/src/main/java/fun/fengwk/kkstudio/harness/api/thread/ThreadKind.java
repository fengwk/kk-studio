package fun.fengwk.kkstudio.harness.api.thread;

/** Thread 的不可变创建用途。 */
public enum ThreadKind {
  /** 用户会话主 Thread 与用户创建的分支。 */
  USER,
  /** task 工具派生的子代理。 */
  SUBAGENT,
  /** 压缩分支。 */
  COMPACTION,
  /** 插件 Prompt 任务。 */
  PLUGIN_TASK
}

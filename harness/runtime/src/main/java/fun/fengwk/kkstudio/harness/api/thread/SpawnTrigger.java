package fun.fengwk.kkstudio.harness.api.thread;

/** 创建 Thread 的原始来源；与 Thread 一起不可变记录，用于审计与来源区分，不改变执行语义。 */
public enum SpawnTrigger {
  /** 用户直接创建的会话或分支。 */
  USER,
  /** 模型通过 {@code task} 等工具发起的子代理。 */
  MODEL_TOOL,
  /** 运行时自身发起的内部执行，如压缩。 */
  RUNTIME,
  /** 上层控制器编排发起的任务。 */
  CONTROLLER,
  /** 插件发起的一次性 Prompt 任务。 */
  PLUGIN
}

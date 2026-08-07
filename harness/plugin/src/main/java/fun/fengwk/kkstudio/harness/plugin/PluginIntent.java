package fun.fengwk.kkstudio.harness.plugin;

/**
 * 插件向 Harness 声明的声明式状态变更意图。
 *
 * <p>意图是纯声明，本 slice 不提供执行器；Harness 未来按意图类型追加对应 Entry 并校验 ownership。意图不能表达任意 状态转换——没有泛化 command/map
 * 通道。
 */
public sealed interface PluginIntent
    permits AppendCustomEntry, AppendCustomMessage, ContinueModel {}

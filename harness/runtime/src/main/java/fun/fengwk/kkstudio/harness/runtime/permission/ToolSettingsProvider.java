package fun.fengwk.kkstudio.harness.runtime.permission;

/**
 * 提供部署范围内唯一的 normalized Tool settings snapshot。定义于 {@code harness/runtime/.../permission} 之下，使
 * Runtime Tool worker 可以在不依赖 Core deployment 模块的前提下注入它。
 */
public interface ToolSettingsProvider {
  ToolSettings get();
}

package fun.fengwk.kkstudio.platform.harness.tool.permission;

/** 提供部署范围内唯一的 normalized Tool settings snapshot，供 Tool 执行边界的 preflight 在每次评估前现读。 */
public interface ToolSettingsProvider {
  ToolSettings get();
}

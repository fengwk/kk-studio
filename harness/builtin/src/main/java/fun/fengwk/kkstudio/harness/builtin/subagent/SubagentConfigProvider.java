package fun.fengwk.kkstudio.harness.builtin.subagent;

/** 向 task/subagent 决策点提供每次现读的 {@link SubagentConfig}（aiRuntime 配置 live 生效，无需重启）。 */
@FunctionalInterface
public interface SubagentConfigProvider {

  /** 返回当前生效的 subagent 并发/预算配置；每次决策点调用。 */
  SubagentConfig subagentConfig();
}

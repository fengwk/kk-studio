package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;

import java.util.Objects;

/**
 * 冻结后的单一 Tool 贡献契约。
 *
 * @param id 贡献的全局唯一 ContributionId
 * @param definition 模型可见的工具统一定义
 * @param tool 底层可执行 Tool 实现
 * @param requirements 声明的执行前置需求
 * @param priority 排序优先级
 */
public record ToolContribution(
    ContributionId id,
    AgentToolDefinition definition,
    Tool tool,
    ToolRequirements requirements,
    int priority) {

  public ToolContribution {
    id = Objects.requireNonNull(id, "id");
    definition = Objects.requireNonNull(definition, "definition");
    tool = Objects.requireNonNull(tool, "tool");
    requirements = Objects.requireNonNull(requirements, "requirements");
    if (!definition.descriptor().equals(tool.descriptor())) {
      throw new IllegalArgumentException("tool definition descriptor must match tool descriptor");
    }
    if (!requirements.equals(tool.requirements())) {
      throw new IllegalArgumentException("tool requirements must match tool.requirements()");
    }
  }
}

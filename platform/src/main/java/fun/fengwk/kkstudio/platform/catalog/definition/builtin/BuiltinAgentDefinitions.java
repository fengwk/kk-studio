package fun.fengwk.kkstudio.platform.catalog.definition.builtin;

import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.BuiltinAgentPrompts;

import java.util.List;

/**
 * 系统内置 Agent 的集中静态定义。
 *
 * <p>这里持有全部内置 Agent 的保留名称、初始系统提示词与初始执行配置，是保留身份、启动初始化与生命周期保护共用的唯一事实来源；不引入插件
 * registry、配置扩展点或按名称散落的特判。内置 Agent 的名称与存在性由系统持有，用户只能编辑其 prompt/model/tools/skills 等普通执行配置。
 *
 * <p>当前只有压缩 Agent {@code compaction}：初始复用摘要 system prompt，无 tools/skills/subagents，模型显式未配置。
 */
public final class BuiltinAgentDefinitions {

  /** 压缩内置 Agent 的保留名称；压缩执行路径按此名称通过普通 Agent 目录读取定义。 */
  public static final String COMPACTION_NAME = "compaction";

  private static final List<BuiltinAgent> DEFINITIONS =
      List.of(
          new BuiltinAgent(
              COMPACTION_NAME,
              BuiltinAgentPrompts.compactionSummarizationSystemPrompt(),
              emptyConfig()));

  private BuiltinAgentDefinitions() {}

  /** 全部内置 Agent 的不可变定义集合。 */
  public static List<BuiltinAgent> definitions() {
    return DEFINITIONS;
  }

  /** 名称是否为系统保留的内置 Agent 名称。 */
  public static boolean isReservedName(String name) {
    for (BuiltinAgent definition : DEFINITIONS) {
      if (definition.name().equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static AgentDefinitionConfigDTO emptyConfig() {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    config.setInheritParentEnvironment(Boolean.TRUE);
    return config;
  }

  /** 单个内置 Agent 的静态定义：保留名称、初始系统提示词与初始执行配置。 */
  public record BuiltinAgent(String name, String systemPrompt, AgentDefinitionConfigDTO config) {

    public BuiltinAgent {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("builtin agent name must not be blank");
      }
      if (config == null) {
        throw new IllegalArgumentException("builtin agent config must not be null");
      }
    }
  }
}

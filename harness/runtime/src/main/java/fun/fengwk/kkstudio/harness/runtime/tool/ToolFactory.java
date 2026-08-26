package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.Objects;

/** 按冻结 Agent tool 定义创建工具实现。 */
public interface ToolFactory {

  AgentToolDefinition definition();

  Tool create();

  /** 普通 Tool 默认 priority 为 0。 */
  default int priority() {
    return 0;
  }

  static ToolFactory singleton(AgentToolId id, Tool tool) {
    return singleton(id, tool, ToolVisibility.SELECTABLE, 0);
  }

  static ToolFactory singleton(AgentToolId id, Tool tool, ToolVisibility visibility) {
    return singleton(id, tool, visibility, 0);
  }

  static ToolFactory singleton(AgentToolId id, Tool tool, ToolVisibility visibility, int priority) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(visibility, "visibility");
    ToolDescriptor frozenDescriptor = Objects.requireNonNull(tool.descriptor(), "tool.descriptor");
    AgentToolDefinition frozenDefinition =
        new AgentToolDefinition(id, frozenDescriptor, visibility, AgentToolBackend.HOST);
    return new ToolFactory() {
      @Override
      public AgentToolDefinition definition() {
        return frozenDefinition;
      }

      @Override
      public Tool create() {
        requireMatchingDescriptor(frozenDefinition, tool);
        return tool;
      }

      @Override
      public int priority() {
        return priority;
      }
    };
  }

  private static void requireMatchingDescriptor(AgentToolDefinition frozenDefinition, Tool tool) {
    ToolDescriptor actual = Objects.requireNonNull(tool.descriptor(), "created tool descriptor");
    if (!frozenDefinition.descriptor().equals(actual)) {
      throw new IllegalStateException("created tool descriptor does not match frozen descriptor");
    }
  }
}

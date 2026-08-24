package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.Objects;

/** 按冻结描述创建工具实现。 */
public interface ToolFactory {

  ToolDescriptor descriptor();

  Tool create();

  /** 普通 Tool 默认可选；需要内部能力时装配方必须显式声明 {@link ToolVisibility#INTERNAL}。 */
  default ToolVisibility visibility() {
    return ToolVisibility.SELECTABLE;
  }

  /** 普通 Tool 默认 priority 为 0。 */
  default int priority() {
    return 0;
  }

  static ToolFactory singleton(Tool tool) {
    return singleton(tool, ToolVisibility.SELECTABLE, 0);
  }

  static ToolFactory singleton(Tool tool, ToolVisibility visibility) {
    return singleton(tool, visibility, 0);
  }

  static ToolFactory singleton(Tool tool, ToolVisibility visibility, int priority) {
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(visibility, "visibility");
    ToolDescriptor frozenDescriptor = Objects.requireNonNull(tool.descriptor(), "tool.descriptor");
    return new ToolFactory() {
      @Override
      public ToolDescriptor descriptor() {
        return frozenDescriptor;
      }

      @Override
      public Tool create() {
        requireMatchingDescriptor(frozenDescriptor, tool);
        return tool;
      }

      @Override
      public ToolVisibility visibility() {
        return visibility;
      }

      @Override
      public int priority() {
        return priority;
      }
    };
  }

  private static void requireMatchingDescriptor(ToolDescriptor frozenDescriptor, Tool tool) {
    ToolDescriptor actual = Objects.requireNonNull(tool.descriptor(), "created tool descriptor");
    if (!frozenDescriptor.name().equals(actual.name())
        || !frozenDescriptor.version().equals(actual.version())) {
      throw new IllegalStateException(
          "created tool descriptor does not match frozen name and version");
    }
  }
}

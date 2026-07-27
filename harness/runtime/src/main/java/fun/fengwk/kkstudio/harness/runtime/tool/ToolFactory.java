package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.Objects;

/** 按冻结描述创建工具实现。 */
public interface ToolFactory {

  ToolDescriptor descriptor();

  Tool create();

  static ToolFactory singleton(Tool tool) {
    Objects.requireNonNull(tool, "tool");
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

package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Environment Daemon 本地可执行 Tool SPI 的注册表。 */
public final class DaemonToolRegistry {

  private final Map<String, Tool> tools = new LinkedHashMap<>();
  private boolean frozen;

  /** 注册一个由本 Daemon 本地执行的工具。 */
  public synchronized void register(Tool tool) {
    if (frozen) {
      throw new IllegalStateException("tool registry is frozen");
    }
    tool = Objects.requireNonNull(tool, "tool");
    ToolDescriptor descriptor = Objects.requireNonNull(tool.descriptor(), "tool.descriptor()");
    if (tools.putIfAbsent(descriptor.name(), tool) != null) {
      throw new IllegalArgumentException("tool is already registered: " + descriptor.name());
    }
  }

  /** 按稳定工具名查询本地实现。 */
  public synchronized Optional<Tool> find(String toolName) {
    return Optional.ofNullable(tools.get(toolName));
  }

  /** 返回本地注册工具的稳定描述列表。 */
  public synchronized Collection<ToolDescriptor> descriptors() {
    return tools.values().stream().map(Tool::descriptor).toList();
  }

  synchronized void freeze() {
    frozen = true;
  }
}

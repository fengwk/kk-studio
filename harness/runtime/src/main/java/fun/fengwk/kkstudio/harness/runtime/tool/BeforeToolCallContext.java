package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import java.nio.file.Path;
import java.util.Objects;

/** beforeToolCall 当前不可变输入。 */
public record BeforeToolCallContext(
    ToolBinding binding,
    ToolCall call,
    ToolSettings settings,
    boolean yoloEnabled,
    Path workdir,
    Path environmentRoot) {
  public BeforeToolCallContext {
    binding = Objects.requireNonNull(binding, "binding");
    call = Objects.requireNonNull(call, "call");
    settings = Objects.requireNonNull(settings, "settings");
    workdir = Objects.requireNonNull(workdir, "workdir").toAbsolutePath().normalize();
    environmentRoot =
        Objects.requireNonNull(environmentRoot, "environmentRoot").toAbsolutePath().normalize();
  }
}

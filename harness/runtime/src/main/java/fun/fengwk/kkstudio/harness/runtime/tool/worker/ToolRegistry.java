package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import java.util.Optional;

/** Resolves the currently registered implementation for a persisted frozen name and version. */
public interface ToolRegistry {
  Optional<Tool> find(String name, String version);
}

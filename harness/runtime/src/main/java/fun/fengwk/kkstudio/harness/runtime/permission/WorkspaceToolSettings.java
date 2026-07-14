package fun.fengwk.kkstudio.harness.runtime.permission;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Workspace 中规范化的 ordered permission rules 与默认 YOLO。 */
public record WorkspaceToolSettings(
    Map<String, List<PermissionRule>> permission, boolean defaultYolo) {

  public static final WorkspaceToolSettings DEFAULT = new WorkspaceToolSettings(Map.of(), false);

  public WorkspaceToolSettings {
    Objects.requireNonNull(permission, "permission");
    Map<String, List<PermissionRule>> copy = new LinkedHashMap<>();
    permission.forEach(
        (tool, rules) -> {
          if (tool == null || tool.isBlank()) {
            throw new IllegalArgumentException("permission tool name must not be blank");
          }
          copy.put(tool, List.copyOf(Objects.requireNonNull(rules, "rules")));
        });
    permission = Collections.unmodifiableMap(copy);
  }

  public List<PermissionRule> rulesFor(String toolName) {
    return permission.getOrDefault(toolName, List.of());
  }
}

package fun.fengwk.kkstudio.harness.runtime.permission;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 以精确 {@code *} 或模型可见 tool name 为 key 的 ordered permission rules 与默认 YOLO。 */
public record ToolSettings(Map<String, List<PermissionRule>> permission, boolean defaultYolo) {

  public static final ToolSettings DEFAULT = new ToolSettings(Map.of(), false);

  public ToolSettings {
    Objects.requireNonNull(permission, "permission");
    Map<String, List<PermissionRule>> copy = new LinkedHashMap<>();
    permission.forEach(
        (key, rules) -> {
          PermissionKeyValidator.requireValid(key, "permission key");
          copy.put(key, List.copyOf(Objects.requireNonNull(rules, "rules")));
        });
    permission = Collections.unmodifiableMap(copy);
  }

  public List<PermissionRule> globalRules() {
    return permission.getOrDefault(PermissionKeyValidator.GLOBAL_KEY, List.of());
  }

  public List<PermissionRule> rulesFor(String toolName) {
    return permission.getOrDefault(Objects.requireNonNull(toolName, "toolName"), List.of());
  }
}

package fun.fengwk.kkstudio.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DefaultToolRegistry 是基于内存的工具注册表实现。
 *
 * @author fengwk
 */
public class DefaultToolRegistry implements ToolRegistry {

  private final Map<String, ToolRegistration> registrations = new LinkedHashMap<>();

  @Override
  public synchronized void register(ToolRegistration registration) {
    if (registration == null) {
      throw new IllegalArgumentException("registration must not be null");
    }
    String name = requireNonBlank(registration.getName(), "registration.name");
    ToolInfo toolInfo = requireNonNull(registration.getToolInfo(), "registration.toolInfo");
    Tool tool = requireNonNull(registration.getTool(), "registration.tool");
    String toolInfoName = requireNonBlank(toolInfo.getName(), "registration.toolInfo.name");
    if (!name.equals(toolInfoName)) {
      throw new IllegalArgumentException(
          "tool registration name does not match tool info name: " + name);
    }
    if (tool.timeoutSeconds() < 0) {
      throw new IllegalArgumentException("tool timeoutSeconds must not be negative: " + name);
    }
    if (registrations.containsKey(name)) {
      throw new IllegalArgumentException("tool already registered: " + name);
    }
    registrations.put(name, registration);
  }

  @Override
  public synchronized ToolRegistration get(String name) {
    if (name == null) {
      return null;
    }
    return registrations.get(name);
  }

  private <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  private String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}

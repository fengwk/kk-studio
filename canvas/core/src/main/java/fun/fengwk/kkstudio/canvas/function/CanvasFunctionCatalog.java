package fun.fengwk.kkstudio.canvas.function;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 启动时冻结的 Canvas Function 定义与 adapter 注册目录，键是全局唯一的函数名。 */
public final class CanvasFunctionCatalog {

  private final Map<String, RegisteredFunction> byName;
  private final List<RegisteredFunction> ordered;

  private CanvasFunctionCatalog(
      Map<String, RegisteredFunction> byName, List<RegisteredFunction> ordered) {
    this.byName = Map.copyOf(byName);
    this.ordered = List.copyOf(ordered);
  }

  public static CanvasFunctionCatalog from(Collection<? extends CanvasFunctionAdapter> adapters) {
    Objects.requireNonNull(adapters, "adapters");
    Map<String, RegisteredFunction> registered = new LinkedHashMap<>();
    for (CanvasFunctionAdapter adapter : adapters) {
      Objects.requireNonNull(adapter, "adapter");
      List<CanvasFunctionDefinition> functions =
          List.copyOf(Objects.requireNonNull(adapter.functions(), "adapter.functions"));
      if (functions.isEmpty()) {
        throw new IllegalArgumentException(
            "CanvasFunctionAdapter must declare at least one function");
      }
      for (CanvasFunctionDefinition function : functions) {
        Objects.requireNonNull(function, "adapter.functions contains null");
      }
      boolean enabled = adapter.enabled();
      String unavailableReason = adapter.unavailableReason();
      for (CanvasFunctionDefinition function : functions) {
        RegisteredFunction entry =
            new RegisteredFunction(function, adapter, enabled, unavailableReason);
        if (registered.putIfAbsent(function.name(), entry) != null) {
          throw new IllegalArgumentException("duplicate Canvas Function name: " + function.name());
        }
      }
    }
    List<RegisteredFunction> ordered = new ArrayList<>(registered.values());
    ordered.sort(Comparator.comparing(value -> value.function().name()));
    return new CanvasFunctionCatalog(registered, ordered);
  }

  public List<RegisteredFunction> list() {
    return ordered;
  }

  public RegisteredFunction require(String name) {
    RegisteredFunction registered = byName.get(name);
    if (registered == null) {
      throw new IllegalArgumentException("unknown Canvas Function: " + name);
    }
    return registered;
  }

  public record RegisteredFunction(
      CanvasFunctionDefinition function,
      CanvasFunctionAdapter adapter,
      boolean enabled,
      String unavailableReason) {

    public RegisteredFunction {
      Objects.requireNonNull(function, "function");
      Objects.requireNonNull(adapter, "adapter");
      if (enabled && unavailableReason != null) {
        throw new IllegalArgumentException("enabled adapter must not expose unavailableReason");
      }
      if (!enabled && (unavailableReason == null || unavailableReason.isBlank())) {
        throw new IllegalArgumentException("disabled adapter must expose unavailableReason");
      }
    }

    public void requireAvailable() {
      if (!enabled) {
        throw new IllegalArgumentException("Canvas Function is unavailable: " + unavailableReason);
      }
    }
  }
}

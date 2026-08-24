package fun.fengwk.kkstudio.canvas.function;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 启动时冻结的 Canvas Function model 与 adapter 注册目录。 */
public final class CanvasFunctionCatalog {

  private final Map<String, RegisteredModel> byKey;
  private final List<RegisteredModel> ordered;

  private CanvasFunctionCatalog(Map<String, RegisteredModel> byKey, List<RegisteredModel> ordered) {
    this.byKey = Map.copyOf(byKey);
    this.ordered = List.copyOf(ordered);
  }

  public static CanvasFunctionCatalog from(Collection<? extends CanvasFunctionAdapter> adapters) {
    Objects.requireNonNull(adapters, "adapters");
    Map<String, RegisteredModel> registered = new LinkedHashMap<>();
    for (CanvasFunctionAdapter adapter : adapters) {
      Objects.requireNonNull(adapter, "adapter");
      List<CanvasFunctionModel> models =
          List.copyOf(Objects.requireNonNull(adapter.models(), "adapter.models"));
      if (models.isEmpty()) {
        throw new IllegalArgumentException("CanvasFunctionAdapter must declare at least one model");
      }
      for (CanvasFunctionModel model : models) {
        Objects.requireNonNull(model, "adapter.models contains null");
      }
      boolean enabled = adapter.enabled();
      String unavailableReason = adapter.unavailableReason();
      for (CanvasFunctionModel model : models) {
        RegisteredModel registeredModel =
            new RegisteredModel(model, adapter, enabled, unavailableReason);
        if (registered.putIfAbsent(model.key(), registeredModel) != null) {
          throw new IllegalArgumentException("duplicate Canvas Function model key: " + model.key());
        }
      }
    }
    List<RegisteredModel> ordered = new ArrayList<>(registered.values());
    ordered.sort(Comparator.comparing(value -> value.model().key()));
    return new CanvasFunctionCatalog(registered, ordered);
  }

  public List<RegisteredModel> list() {
    return ordered;
  }

  public RegisteredModel require(String modelKey) {
    RegisteredModel registered = byKey.get(modelKey);
    if (registered == null) {
      throw new IllegalArgumentException("unknown Canvas Function model: " + modelKey);
    }
    return registered;
  }

  public record RegisteredModel(
      CanvasFunctionModel model,
      CanvasFunctionAdapter adapter,
      boolean enabled,
      String unavailableReason) {

    public RegisteredModel {
      Objects.requireNonNull(model, "model");
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
        throw new IllegalArgumentException(
            "Canvas Function model is unavailable: " + unavailableReason);
      }
    }
  }
}

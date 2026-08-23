package fun.fengwk.kkstudio.canvas.infra.function;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 启动即冻结并校验 model key 唯一性的 adapter registry。 */
@Component
public final class CanvasFunctionModelRegistry implements CanvasFunctionCatalog {

  private final Map<String, RegisteredModel> byKey;
  private final List<RegisteredModel> ordered;

  public CanvasFunctionModelRegistry(List<CanvasFunctionAdapter> adapters) {
    Map<String, RegisteredModel> registered = new LinkedHashMap<>();
    for (CanvasFunctionAdapter adapter : adapters) {
      Objects.requireNonNull(adapter, "adapter");
      List<CanvasFunctionModel> models =
          List.copyOf(Objects.requireNonNull(adapter.models(), "adapter.models"));
      if (models.isEmpty()) {
        throw new IllegalArgumentException("CanvasFunctionAdapter must declare at least one model");
      }
      if (adapter.enabled() && adapter.unavailableReason() != null) {
        throw new IllegalArgumentException("enabled adapter must not expose unavailableReason");
      }
      if (!adapter.enabled()
          && (adapter.unavailableReason() == null || adapter.unavailableReason().isBlank())) {
        throw new IllegalArgumentException("disabled adapter must expose unavailableReason");
      }
      for (CanvasFunctionModel model : models) {
        RegisteredModel value = new RegisteredModel(model, adapter);
        if (registered.putIfAbsent(model.key(), value) != null) {
          throw new IllegalArgumentException("duplicate Canvas Function model key: " + model.key());
        }
      }
    }
    byKey = Map.copyOf(registered);
    List<RegisteredModel> sorted = new ArrayList<>(registered.values());
    sorted.sort(Comparator.comparing(value -> value.model().key()));
    ordered = List.copyOf(sorted);
  }

  @Override
  public List<RegisteredModel> list() {
    return ordered;
  }

  @Override
  public RegisteredModel require(String modelKey) {
    RegisteredModel registered = byKey.get(modelKey);
    if (registered == null) {
      throw new IllegalArgumentException("unknown Canvas Function model: " + modelKey);
    }
    return registered;
  }
}

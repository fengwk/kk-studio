package fun.fengwk.kkstudio.harness.runtime.interaction;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable lookup of unique generic Interaction handlers. */
public final class InteractionHandlerRegistry {
  private final Map<String, InteractionHandler> handlers;

  public InteractionHandlerRegistry(Collection<? extends InteractionHandler> handlers) {
    Objects.requireNonNull(handlers, "handlers");
    Map<String, InteractionHandler> collected = new LinkedHashMap<>();
    for (InteractionHandler handler : handlers) {
      InteractionHandler requiredHandler = Objects.requireNonNull(handler, "handler");
      String type = requireType(requiredHandler.type());
      if (collected.putIfAbsent(type, requiredHandler) != null) {
        throw new IllegalArgumentException("duplicate interaction handler type: " + type);
      }
    }
    this.handlers = Map.copyOf(collected);
  }

  /** Returns the registered handler or rejects an unknown persisted handler type. */
  public InteractionHandler require(String type) {
    String requiredType = requireType(type);
    InteractionHandler handler = handlers.get(requiredType);
    if (handler == null) {
      throw new IllegalArgumentException("unknown interaction handler: " + requiredType);
    }
    return handler;
  }

  private static String requireType(String type) {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("interaction handler type must not be blank");
    }
    if (type.length() > 64) {
      throw new IllegalArgumentException("interaction handler type must be <= 64 chars");
    }
    return type;
  }
}

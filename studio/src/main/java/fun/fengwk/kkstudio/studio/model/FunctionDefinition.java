package fun.fengwk.kkstudio.studio.model;

import java.util.List;
import java.util.Objects;

/**
 * Catalog entry for a callable Function.
 *
 * <p>Generation providers are not wired yet; system executors remain stubs until real adapters
 * land.
 */
public record FunctionDefinition(
    FunctionRef ref,
    FunctionScope scope,
    Long workspaceId,
    String displayName,
    String description,
    List<String> inputKeys,
    List<String> outputChannelKeys,
    String configSchemaJson,
    boolean cacheable) {

  public FunctionDefinition {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(inputKeys, "inputKeys");
    Objects.requireNonNull(outputChannelKeys, "outputChannelKeys");
    Objects.requireNonNull(configSchemaJson, "configSchemaJson");
    inputKeys = List.copyOf(inputKeys);
    outputChannelKeys = List.copyOf(outputChannelKeys);
  }
}

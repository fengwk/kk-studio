package fun.fengwk.kkstudio.harness.runtime.configuration;

import java.util.Objects;

/**
 * Command-time live resource → immutable {@link RuntimeConfigSnapshot} resolution SPI.
 *
 * <p>Implementations may read durable definitions, models, providers, ready environments and {@code
 * ToolFactories} descriptors. They must not create providers or perform model I/O. Pure in-memory
 * YOLO policy replacement is owned by runtime command orchestration, not this SPI.
 */
public interface RuntimeConfigSource {

  /** Resolves a full snapshot from a live agent definition and the requested yolo flag. */
  RuntimeConfigSnapshot resolveAgent(long definitionId, boolean yoloEnabled);

  /**
   * Resolves an Agent snapshot and applies the Chat/Thread Environment identity without consulting
   * live Environment state.
   */
  default RuntimeConfigSnapshot resolveAgent(
      long definitionId, String environmentName, boolean yoloEnabled) {
    return resolveAgent(definitionId, yoloEnabled).withEnvironmentName(environmentName);
  }

  /**
   * Resolves a replacement Agent while retaining every non-Agent field from the current snapshot.
   */
  default RuntimeConfigSnapshot replaceAgent(RuntimeConfigSnapshot current, long definitionId) {
    Objects.requireNonNull(current, "current");
    return resolveAgent(definitionId, current.environmentName(), current.yoloEnabled())
        .withEnvironmentName(current.environmentName());
  }

  /**
   * Copies {@code current} and replaces only the live model/variant, re-validating tool capability.
   */
  RuntimeConfigSnapshot replaceModel(
      RuntimeConfigSnapshot current, long modelId, String requestedVariant);
}

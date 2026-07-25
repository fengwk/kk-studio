package fun.fengwk.kkstudio.harness.runtime.configuration;

/**
 * Command-time live resource → immutable {@link RuntimeConfigSnapshot} resolution SPI.
 *
 * <p>Implementations may read durable definitions, models, providers, ready environments and
 * extension descriptors. They must not create providers or perform model I/O. Pure in-memory YOLO
 * policy replacement is owned by runtime command orchestration, not this SPI.
 */
public interface RuntimeConfigSource {

  /** Resolves a full snapshot from a live agent definition and the requested yolo flag. */
  RuntimeConfigSnapshot resolveAgent(long definitionId, boolean yoloEnabled);

  /**
   * Copies {@code current} and replaces only the live model/variant, re-validating tool capability.
   */
  RuntimeConfigSnapshot replaceModel(
      RuntimeConfigSnapshot current, long modelId, String requestedVariant);
}

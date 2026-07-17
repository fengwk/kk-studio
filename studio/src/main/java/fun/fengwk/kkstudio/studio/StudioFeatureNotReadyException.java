package fun.fengwk.kkstudio.studio;

/**
 * Domain-level signal that a Studio port is intentionally unimplemented.
 *
 * <p>Transport adapters map this to HTTP 501. It must not be used for validation errors.
 */
public class StudioFeatureNotReadyException extends UnsupportedOperationException {

  public StudioFeatureNotReadyException(String feature) {
    super("Studio feature not ready: " + feature);
  }
}

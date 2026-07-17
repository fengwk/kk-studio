package fun.fengwk.kkstudio.core.studio.service;

/** Temporary boundary for unfinished Studio adapters. */
public class StudioNotImplementedException extends UnsupportedOperationException {

  public StudioNotImplementedException(String feature) {
    super("Studio feature not implemented yet: " + feature);
  }
}

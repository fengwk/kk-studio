package fun.fengwk.kkstudio.harness.tool.daemon;

/**
 * Canonical "empty" Daemon CAPABILITIES payload. Newly registered Environments expose this payload
 * until their daemon uploads real descriptors.
 */
public final class DaemonEmptyCapabilities {

  private DaemonEmptyCapabilities() {}

  /** Canonical empty CAPABILITIES JSON text: {@code {"tools":[]}}. */
  public static final String JSON = "{\"tools\":[]}";
}

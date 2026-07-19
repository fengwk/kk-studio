package fun.fengwk.kkstudio.harness.tool.daemon;

/**
 * Canonical "empty" Daemon CAPABILITIES payload. Environments expose this payload until their
 * daemon uploads real tools and skills.
 */
public final class DaemonEmptyCapabilities {

  private DaemonEmptyCapabilities() {}

  /** Canonical empty CAPABILITIES JSON text: {@code {"tools":[],"skills":[]}}. */
  public static final String JSON = "{\"tools\":[],\"skills\":[]}";
}

package fun.fengwk.kkstudio.studio;

/**
 * Workspace policy for the current single-instance product.
 *
 * <p>kk-studio does not implement multi-tenant membership yet. All Studio APIs still carry {@code
 * workspaceId} so the contract can grow later; callers must use {@link #DEFAULT_ID} until a real
 * Workspace aggregate exists.
 */
public final class StudioWorkspaces {

  /** Sole workspace id for the global single-instance deployment. */
  public static final long DEFAULT_ID = 1L;

  private StudioWorkspaces() {}

  public static void requireDefault(long workspaceId) {
    if (workspaceId != DEFAULT_ID) {
      throw new IllegalArgumentException(
          "Only the default workspace is supported currently: expected "
              + DEFAULT_ID
              + " but was "
              + workspaceId);
    }
  }
}

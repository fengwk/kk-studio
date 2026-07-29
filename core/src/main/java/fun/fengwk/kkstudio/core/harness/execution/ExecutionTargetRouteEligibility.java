package fun.fengwk.kkstudio.core.harness.execution;

import java.util.Set;

/**
 * Snapshot of route keys currently eligible on this node.
 *
 * <p>For the harness activation queue, the typical implementation derives {@link #readyRouteKeys()}
 * from {@code LiveEnvironmentRegistry.listReady()}. NULL-route targets are always eligible;
 * non-NULL-route targets are eligible only when this snapshot contains their key.
 *
 * <p>The snapshot is read at the start of each drain iteration so a node that is not locally READY
 * for an environment never busy-loops on that environment's queued targets.
 */
@FunctionalInterface
public interface ExecutionTargetRouteEligibility {

  /** Returns the immutable set of route keys currently eligible locally. */
  Set<String> readyRouteKeys();

  /** Constant snapshot for tests / non-routed use. */
  static ExecutionTargetRouteEligibility empty() {
    return Set::of;
  }

  /** Adapter from a {@link Set}. */
  static ExecutionTargetRouteEligibility of(Set<String> keys) {
    return () -> keys == null ? Set.of() : Set.copyOf(keys);
  }
}

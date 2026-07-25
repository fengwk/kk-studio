/**
 * Durable generic interaction contract.
 *
 * <p>This package owns the framework-free interaction fact, strict raw-JSON boundary, handler SPI,
 * use-case transaction port, and {@link InteractionCoordinator} orchestration (projection, expiry,
 * resolution). Persistence, Spring transactions, HTTP DTOs, and Tool-specific approval policy
 * remain outside this boundary.
 */
package fun.fengwk.kkstudio.harness.runtime.interaction;

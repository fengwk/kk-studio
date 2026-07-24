package fun.fengwk.kkstudio.harness.runtime.interaction;

/**
 * Type-specific Interaction boundary.
 *
 * <p>Handlers project and resolve already validated values only. They must be deterministic and
 * must not start, commit, or roll back a transaction.
 */
public interface InteractionHandler {

  String type();

  InteractionProjection project(InteractionRequest request);

  InteractionResolution resolve(InteractionRequest request, InteractionResponse response);
}

package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.Objects;

/**
 * Immutable create-thread request: the initial complete {@link BranchSettings} of the new Session
 * ROOT and the Thread YOLO runtime policy.
 *
 * <p>{@code title} is an optional user-visible label and is validated by {@link
 * fun.fengwk.kkstudio.harness.runtime.session.Session}. There is deliberately no {@code
 * createRequestId}: the 7-table model has no create idempotency key, so {@link
 * HarnessRuntime#createThread} is non-idempotent.
 */
public record CreateThreadCommand(
    String title, BranchSettings branchSettings, boolean yoloEnabled) {

  public CreateThreadCommand {
    branchSettings = Objects.requireNonNull(branchSettings, "branchSettings");
  }
}

package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Owner;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** A registered resource is visible exclusively to its owner, even for administrators. */
@Service
public class ResourceAccess {
  private final ObjectProvider<ResourceOwnership> ownership;
  private final ObjectProvider<HarnessRuntime> runtimes;

  public ResourceAccess(
      ObjectProvider<ResourceOwnership> ownership, ObjectProvider<HarnessRuntime> runtimes) {
    this.ownership = ownership;
    this.runtimes = runtimes;
  }

  public Optional<Owner> owner(Kind kind, UUID id) {
    return Optional.ofNullable(ownership.getIfAvailable())
        .flatMap(port -> port.findOwner(kind, id));
  }

  public boolean allows(StudioPrincipal principal, Kind kind, UUID id) {
    return owner(kind, id)
        .map(owner -> owner.ownerUserId().equals(principal.userId()))
        .orElseGet(() -> kind == Kind.SESSION && principal.role() == StudioRole.ADMIN);
  }

  public Optional<UUID> rootSession(UUID threadId) {
    var runtime = runtimes.getObject();
    return runtime.findRootThreadId(threadId).flatMap(runtime::findThreadSessionId);
  }

  public boolean allowsThread(StudioPrincipal principal, UUID threadId) {
    return rootSession(threadId)
        .filter(session -> allows(principal, Kind.SESSION, session))
        .isPresent();
  }

  public boolean allowInteractionRoot(StudioPrincipal principal, UUID rootThreadId) {
    var runtime = runtimes.getObject();
    return runtime
        .findThreadSessionId(rootThreadId)
        .filter(
            session ->
                allowsInteraction(
                    principal,
                    owner(Kind.SESSION, session),
                    () ->
                        runtime.findExecutionRootKind(rootThreadId).orElse(null)
                            == ThreadKind.PLUGIN_TASK))
        .isPresent();
  }

  public static boolean allowsInteraction(
      StudioPrincipal principal, Optional<Owner> owner, BooleanSupplier pluginRoot) {
    return owner
        .map(value -> value.ownerUserId().equals(principal.userId()))
        .orElseGet(() -> principal.role() == StudioRole.ADMIN && pluginRoot.getAsBoolean());
  }

  public void requireThread(StudioPrincipal principal, UUID threadId) {
    if (!allowsThread(principal, threadId)) {
      throw notFound();
    }
  }

  public void require(StudioPrincipal principal, Kind kind, UUID id) {
    if (!allows(principal, kind, id)) {
      throw notFound();
    }
  }

  public static DomainException notFound() {
    return DomainException.notFoundReason("RESOURCE_NOT_FOUND", "Resource not found");
  }
}

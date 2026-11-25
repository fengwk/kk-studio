package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Owner;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

class ResourceAccessTest {
  private ObjectProvider<ResourceOwnership> ownershipProvider;
  private ObjectProvider<HarnessRuntime> runtimeProvider;
  private ResourceOwnership ownership;
  private HarnessRuntime runtime;
  private ResourceAccess access;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ownershipProvider = mock(ObjectProvider.class);
    runtimeProvider = mock(ObjectProvider.class);
    ownership = mock(ResourceOwnership.class);
    runtime = mock(HarnessRuntime.class);
    when(ownershipProvider.getIfAvailable()).thenReturn(ownership);
    when(runtimeProvider.getObject()).thenReturn(runtime);
    access = new ResourceAccess(ownershipProvider, runtimeProvider);
  }

  @Test
  void registeredResourcesAreVisibleOnlyToTheirOwnerIncludingAdmins() {
    UUID id = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    Owner owner = new Owner(UUID.randomUUID(), "space", ownerId);
    when(ownership.findOwner(Kind.CANVAS, id)).thenReturn(Optional.of(owner));

    assertEquals(Optional.of(owner), access.owner(Kind.CANVAS, id));
    assertTrue(access.allows(principal(ownerId, StudioRole.USER), Kind.CANVAS, id));
    assertFalse(access.allows(principal(UUID.randomUUID(), StudioRole.ADMIN), Kind.CANVAS, id));
  }

  @Test
  void missingOwnershipAllowsOnlyAdminSessionFallback() {
    UUID session = UUID.randomUUID();
    when(ownership.findOwner(Kind.SESSION, session)).thenReturn(Optional.empty());

    assertTrue(
        access.allows(principal(UUID.randomUUID(), StudioRole.ADMIN), Kind.SESSION, session));
    assertFalse(
        access.allows(principal(UUID.randomUUID(), StudioRole.USER), Kind.SESSION, session));
    UUID canvas = UUID.randomUUID();
    when(ownership.findOwner(Kind.CANVAS, canvas)).thenReturn(Optional.empty());
    assertFalse(access.allows(principal(UUID.randomUUID(), StudioRole.ADMIN), Kind.CANVAS, canvas));
  }

  @Test
  void absentOwnershipPortUsesTheSameUnregisteredSessionPolicy() {
    @SuppressWarnings("unchecked")
    ObjectProvider<ResourceOwnership> absent = mock(ObjectProvider.class);
    when(absent.getIfAvailable()).thenReturn(null);
    ResourceAccess withoutOwnership = new ResourceAccess(absent, runtimeProvider);

    assertTrue(
        withoutOwnership.allows(
            principal(UUID.randomUUID(), StudioRole.ADMIN), Kind.SESSION, UUID.randomUUID()));
    assertFalse(
        withoutOwnership.allows(
            principal(UUID.randomUUID(), StudioRole.USER), Kind.SESSION, UUID.randomUUID()));
  }

  @Test
  void threadAccessResolvesRootSessionBeforeCheckingOwnership() {
    UUID thread = UUID.randomUUID();
    UUID root = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    when(runtime.findRootThreadId(thread)).thenReturn(Optional.of(root));
    when(runtime.findThreadSessionId(root)).thenReturn(Optional.of(session));
    when(ownership.findOwner(Kind.SESSION, session))
        .thenReturn(Optional.of(new Owner(UUID.randomUUID(), "space", user)));

    assertTrue(access.allowsThread(principal(user, StudioRole.USER), thread));
    verify(runtime).findRootThreadId(thread);
    verify(runtime).findThreadSessionId(root);
  }

  @Test
  void interactionRootUsesAdminPluginRootFallbackOnlyWhenOwnerMissing() {
    UUID root = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    when(runtime.findThreadSessionId(root)).thenReturn(Optional.of(session));
    when(ownership.findOwner(Kind.SESSION, session)).thenReturn(Optional.empty());
    when(runtime.findExecutionRootKind(root)).thenReturn(Optional.of(ThreadKind.PLUGIN_TASK));

    assertTrue(access.allowInteractionRoot(principal(UUID.randomUUID(), StudioRole.ADMIN), root));
    when(runtime.findExecutionRootKind(root)).thenReturn(Optional.of(ThreadKind.USER));
    assertFalse(access.allowInteractionRoot(principal(UUID.randomUUID(), StudioRole.ADMIN), root));
  }

  @Test
  void interactionFallbackIsLazyAndRequireMethodsHideDeniedResources() {
    AtomicBoolean checked = new AtomicBoolean();
    StudioPrincipal user = principal(UUID.randomUUID(), StudioRole.USER);
    assertFalse(
        ResourceAccess.allowsInteraction(
            user,
            Optional.empty(),
            () -> {
              checked.set(true);
              return true;
            }));
    assertFalse(checked.get());

    DomainException failure =
        assertThrows(
            DomainException.class, () -> access.require(user, Kind.CANVAS, UUID.randomUUID()));
    assertEquals(DomainErrorCode.NOT_FOUND, failure.code());
    assertEquals("RESOURCE_NOT_FOUND", failure.reason());
    assertThrows(DomainException.class, () -> access.requireThread(user, UUID.randomUUID()));
  }

  private static StudioPrincipal principal(UUID id, StudioRole role) {
    return new StudioPrincipal(id, "user", role);
  }
}

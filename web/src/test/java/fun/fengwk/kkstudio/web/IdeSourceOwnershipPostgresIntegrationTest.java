package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Owner;

import java.util.Map;
import java.util.UUID;

/** The IDE source authorization gate uses authenticated Studio identities to isolate sessions. */
class IdeSourceOwnershipPostgresIntegrationTest extends WebPostgresTestSupport {

  private static final String PASSWORD = "two-user-password";

  @Autowired private StudioAccountService accounts;

  @Test
  void ideSourceGateAllowsOnlyTheAuthenticatedSessionOwner() {
    StudioPrincipal first = authenticate(accounts, "ide-owner-a");
    StudioPrincipal second = authenticate(accounts, "ide-owner-b");

    UUID firstSession = UUID.randomUUID();
    UUID secondSession = UUID.randomUUID();
    ResourceOwnership ownership =
        (kind, ids) ->
            kind == Kind.SESSION
                ? Map.of(
                    firstSession,
                    new Owner(UUID.randomUUID(), "First workspace", first.userId()),
                    secondSession,
                    new Owner(UUID.randomUUID(), "Second workspace", second.userId()))
                : Map.of();
    ObjectProvider<ResourceOwnership> ownershipProvider = mock(ObjectProvider.class);
    ObjectProvider<HarnessRuntime> runtimeProvider = mock(ObjectProvider.class);
    when(ownershipProvider.getIfAvailable()).thenReturn(ownership);
    ResourceAccess ideSourceGate = new ResourceAccess(ownershipProvider, runtimeProvider);

    assertDoesNotThrow(() -> ideSourceGate.require(first, Kind.SESSION, firstSession));
    assertDoesNotThrow(() -> ideSourceGate.require(second, Kind.SESSION, secondSession));
    assertNotFound(() -> ideSourceGate.require(first, Kind.SESSION, secondSession));
    assertNotFound(() -> ideSourceGate.require(second, Kind.SESSION, firstSession));
  }

  private static StudioPrincipal authenticate(StudioAccountService accounts, String username) {
    accounts.create(username, PASSWORD, StudioRole.USER);
    String token = accounts.login(username, PASSWORD).token();
    return accounts.authenticate(token).orElseThrow();
  }

  private static void assertNotFound(Runnable action) {
    DomainException error = assertThrows(DomainException.class, action::run);
    assertEquals("RESOURCE_NOT_FOUND", error.reason());
  }
}

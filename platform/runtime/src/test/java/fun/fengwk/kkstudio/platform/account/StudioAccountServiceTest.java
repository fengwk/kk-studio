package fun.fengwk.kkstudio.platform.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class StudioAccountServiceTest {
  private static final UUID USER_ID = UUID.randomUUID();
  private static final Instant CREATED = Instant.parse("2025-01-01T00:00:00Z");

  private StudioAccountRepository repository;
  private StudioAccountService service;
  private PasswordHasher hasher;

  @BeforeEach
  void setUp() {
    repository = mock(StudioAccountRepository.class);
    service = new StudioAccountService(repository, List.of());
    hasher = new PasswordHasher();
  }

  @Test
  void loginPersistsOnlyTokenDigestAndReturnsUserProfile() {
    String passwordHash = hasher.encode("correct-password");
    StudioAccountRepository.User user = user("alice", passwordHash, StudioRole.ADMIN, true);
    JsonNode profile = mock(JsonNode.class);
    when(repository.byUsername("alice")).thenReturn(Optional.of(user));
    when(repository.lockById(USER_ID)).thenReturn(Optional.of(user));
    when(repository.profile(USER_ID)).thenReturn(profile);

    StudioAccountService.Login login = service.login("alice", "correct-password");

    assertEquals(StudioRole.ADMIN, login.user().role());
    assertEquals(profile, login.user().profile());
    assertEquals(43, login.token().length());
    verify(repository).deleteExpired(USER_ID);
    verify(repository).session(any(byte[].class), eq(USER_ID));
  }

  @Test
  void loginRejectsMissingUnknownWrongDisabledOrChangedCredentials() {
    assertEquals(
        DomainErrorCode.UNAUTHORIZED,
        assertThrows(DomainException.class, () -> service.login(" ", "password")).code());
    when(repository.byUsername("unknown")).thenReturn(Optional.empty());
    assertEquals(
        "INVALID_CREDENTIALS",
        assertThrows(DomainException.class, () -> service.login("unknown", "password")).reason());

    StudioAccountRepository.User enabled =
        user("alice", hasher.encode("correct-password"), StudioRole.USER, true);
    when(repository.byUsername("alice")).thenReturn(Optional.of(enabled));
    assertThrows(DomainException.class, () -> service.login("alice", "incorrect-password"));
    when(repository.lockById(USER_ID)).thenReturn(Optional.empty());
    assertThrows(DomainException.class, () -> service.login("alice", "correct-password"));
    when(repository.lockById(USER_ID))
        .thenReturn(Optional.of(user("alice", enabled.passwordHash(), StudioRole.USER, false)));
    assertThrows(DomainException.class, () -> service.login("alice", "correct-password"));
    when(repository.lockById(USER_ID))
        .thenReturn(Optional.of(user("renamed", enabled.passwordHash(), StudioRole.USER, true)));
    assertThrows(DomainException.class, () -> service.login("alice", "correct-password"));
    verify(repository, never()).session(any(byte[].class), any());
  }

  @Test
  void authenticationAndLogoutUseHashedTokens() {
    when(repository.sessionPrincipal(any(byte[].class)))
        .thenReturn(Optional.of(user("alice", "unused", StudioRole.USER, true)));
    assertTrue(service.authenticate("token").isPresent());
    assertFalse(service.authenticate(null).isPresent());
    verify(repository, never()).sessionPrincipal(null);
    service.logout("token");
    verify(repository).deleteSession(any(byte[].class));
  }

  @Test
  void changePasswordRequiresValidCurrentSessionAndPasswordThenRevokesOtherSessions() {
    StudioPrincipal principal = new StudioPrincipal(USER_ID, "alice", StudioRole.USER);
    StudioAccountRepository.User user =
        user("alice", hasher.encode("old-password"), StudioRole.USER, true);
    when(repository.lockById(USER_ID)).thenReturn(Optional.of(user));

    assertThrows(
        DomainException.class,
        () -> service.changePassword(principal, "token", "old-password", "short"));
    when(repository.sessionPrincipal(any(byte[].class))).thenReturn(Optional.empty());
    assertEquals(
        "NOT_AUTHENTICATED",
        assertThrows(
                DomainException.class,
                () -> service.changePassword(principal, "token", "old-password", "new-password"))
            .reason());
    when(repository.sessionPrincipal(any(byte[].class))).thenReturn(Optional.of(user));
    assertEquals(
        "WRONG_PASSWORD",
        assertThrows(
                DomainException.class,
                () -> service.changePassword(principal, "token", "bad-password", "new-password"))
            .reason());

    service.changePassword(principal, "token", "old-password", "new-password");

    verify(repository).save(eq(USER_ID), eq("alice"), anyString(), eq(StudioRole.USER), eq(true));
    verify(repository).deleteSessionsExcept(eq(USER_ID), any(byte[].class));
  }

  @Test
  void bootstrapChecksStateUnderTheLockAndPersistsOnlyTheHash() {
    // The account state must not be read before acquiring the same lock as account edits.
    String password = "approved-bootstrap-password";
    service.bootstrapAdmin(password);

    var ordered = inOrder(repository);
    ordered.verify(repository).lockAdminMutations();
    ordered.verify(repository).countEnabledAdmins();
    ordered.verify(repository).hasUsers();
    ordered
        .verify(repository)
        .create(
            any(),
            eq("kkstudio"),
            argThat(hash -> hasher.matches(password, hash)),
            eq(StudioRole.ADMIN));
  }

  @Test
  void bootstrapRejectsMissingAndInvalidPasswordsWithoutWriting() {
    // The normal password bounds also apply to the operator-supplied bootstrap value.
    assertThrows(IllegalStateException.class, () -> service.bootstrapAdmin(null));
    for (String password : List.of("short", " ".repeat(8), "a".repeat(129), "kkstudio")) {
      assertThrows(IllegalStateException.class, () -> service.bootstrapAdmin(password));
    }
    verify(repository, never()).create(any(), anyString(), anyString(), any());
  }

  @Test
  void bootstrapNeverModifiesAnExistingAccountStore() {
    // A nonempty store without an enabled admin is not a fresh installation.
    when(repository.hasUsers()).thenReturn(true);
    assertThrows(IllegalStateException.class, () -> service.bootstrapAdmin("unused-password"));
    when(repository.countEnabledAdmins()).thenReturn(1L);
    service.bootstrapAdmin("unused-password");
    verify(repository, never()).create(any(), anyString(), anyString(), any());
    verify(repository, never()).save(any(), anyString(), anyString(), any(), any());
  }

  @Test
  void createValidatesInputsAndConvertsUniqueViolation() {
    assertThrows(
        DomainException.class, () -> service.create("Alice", "long-password", StudioRole.USER));
    assertThrows(DomainException.class, () -> service.create("alice", "short", StudioRole.USER));
    assertThrows(DomainException.class, () -> service.create("alice", "long-password", null));
    when(repository.byUsername("alice"))
        .thenReturn(Optional.of(user("alice", "hash", StudioRole.USER, true)));
    doThrow(new DuplicateKeyException("duplicate"))
        .when(repository)
        .create(any(), eq("alice"), anyString(), eq(StudioRole.USER));

    assertEquals(
        "USERNAME_EXISTS",
        assertThrows(
                DomainException.class,
                () -> service.create("alice", "long-password", StudioRole.USER))
            .reason());
  }

  @Test
  void createPersistsValidatedAccountAndProjectsListedUsers() {
    StudioAccountRepository.User created = user("alice", "hash", StudioRole.USER, true);
    when(repository.byUsername("alice")).thenReturn(Optional.of(created));
    when(repository.list()).thenReturn(List.of(created));

    assertEquals("alice", service.create("alice", "long-password", StudioRole.USER).username());
    assertEquals("alice", service.list().get(0).username());
    verify(repository).lockAdminMutations();
    verify(repository).create(any(), eq("alice"), anyString(), eq(StudioRole.USER));
  }

  @Test
  void meReturnsTheCurrentUserAndReportsDeletedAccountsAsNotFound() {
    StudioPrincipal principal = new StudioPrincipal(USER_ID, "alice", StudioRole.USER);
    JsonNode profile = mock(JsonNode.class);
    when(repository.byId(USER_ID))
        .thenReturn(Optional.of(user("alice", "hash", StudioRole.USER, true)));
    when(repository.profile(USER_ID)).thenReturn(profile);

    assertEquals(profile, service.me(principal).profile());
    when(repository.byId(USER_ID)).thenReturn(Optional.empty());
    assertEquals(
        DomainErrorCode.NOT_FOUND,
        assertThrows(DomainException.class, () -> service.me(principal)).code());
  }

  @Test
  void updateEnforcesAdminSafetyAndSuspendsDisabledAccounts() {
    StudioAccountRepository.User admin = user("admin", "hash", StudioRole.ADMIN, true);
    when(repository.lockById(USER_ID)).thenReturn(Optional.of(admin));
    assertThrows(DomainException.class, () -> service.update(USER_ID, null, null, null, null));
    when(repository.countEnabledAdmins()).thenReturn(1L);
    assertEquals(
        "LAST_ADMIN",
        assertThrows(
                DomainException.class,
                () -> service.update(USER_ID, null, StudioRole.USER, null, null))
            .reason());
    when(repository.countEnabledAdmins()).thenReturn(2L);
    when(repository.byId(USER_ID))
        .thenReturn(Optional.of(user("admin", "hash", StudioRole.USER, false)));
    AccountScheduleSuspension suspension = mock(AccountScheduleSuspension.class);
    StudioAccountService withSuspension = new StudioAccountService(repository, List.of(suspension));

    withSuspension.update(USER_ID, null, null, null, false);

    verify(repository).deleteSessions(USER_ID);
    verify(suspension).suspend(USER_ID);
  }

  private static StudioAccountRepository.User user(
      String username, String passwordHash, StudioRole role, boolean enabled) {
    return new StudioAccountRepository.User(
        USER_ID, username, passwordHash, role, enabled, CREATED);
  }
}

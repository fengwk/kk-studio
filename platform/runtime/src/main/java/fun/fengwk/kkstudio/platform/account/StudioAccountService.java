package fun.fengwk.kkstudio.platform.account;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.account.dto.StudioAccountDTO;
import fun.fengwk.kkstudio.platform.account.dto.StudioUserDTO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class StudioAccountService {
  public record Login(String token, StudioAccountDTO user) {}

  private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,31}$");
  private final StudioAccountRepository repository;
  private final List<AccountScheduleSuspension> scheduleSuspensions;
  private final PasswordHasher passwords = new PasswordHasher();
  private final SecureRandom random = new SecureRandom();
  private final String dummyPasswordHash = passwords.encode("dummy-password");

  public StudioAccountService(
      StudioAccountRepository repository, List<AccountScheduleSuspension> scheduleSuspensions) {
    this.repository = repository;
    this.scheduleSuspensions = List.copyOf(scheduleSuspensions);
  }

  @Transactional
  public Login login(@Nullable String username, @Nullable String password) {
    if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
      throw invalidCredentials();
    }
    var candidate = repository.byUsername(username).orElse(null);
    boolean valid =
        passwords.matches(
            password, candidate == null ? dummyPasswordHash : candidate.passwordHash());
    if (!valid || candidate == null) {
      throw invalidCredentials();
    }
    var user =
        repository.lockById(candidate.id()).orElseThrow(StudioAccountService::invalidCredentials);
    if (!user.enabled()
        || !user.username().equals(username)
        || !user.passwordHash().equals(candidate.passwordHash())) {
      throw invalidCredentials();
    }
    repository.deleteExpired(user.id());
    byte[] token = new byte[32];
    random.nextBytes(token);
    String encodedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    repository.session(hash(encodedToken.getBytes(StandardCharsets.UTF_8)), user.id());
    return new Login(
        encodedToken,
        new StudioAccountDTO(
            user.id(), user.username(), user.role(), repository.profile(user.id())));
  }

  @Transactional(readOnly = true)
  public Optional<StudioPrincipal> authenticate(String token) {
    if (token == null) {
      return Optional.empty();
    }
    return repository
        .sessionPrincipal(hash(token.getBytes(StandardCharsets.UTF_8)))
        .map(u -> new StudioPrincipal(u.id(), u.username(), u.role()));
  }

  @Transactional(readOnly = true)
  public StudioAccountDTO me(StudioPrincipal principal) {
    var user =
        repository.byId(principal.userId()).orElseThrow(() -> DomainException.notFound("user"));
    return new StudioAccountDTO(
        user.id(), user.username(), user.role(), repository.profile(user.id()));
  }

  @Transactional
  public void logout(String token) {
    repository.deleteSession(hash(token.getBytes(StandardCharsets.UTF_8)));
  }

  @Transactional
  public void changePassword(
      StudioPrincipal principal, String token, String currentPassword, String newPassword) {
    validatePassword(newPassword);
    var user =
        repository.lockById(principal.userId()).orElseThrow(() -> DomainException.notFound("user"));
    byte[] tokenHash = hash(token.getBytes(StandardCharsets.UTF_8));
    if (repository.sessionPrincipal(tokenHash).isEmpty()) {
      throw DomainException.unauthorized("NOT_AUTHENTICATED", "Login required");
    }
    if (!passwords.matches(currentPassword, user.passwordHash())) {
      throw DomainException.validationReason("WRONG_PASSWORD", "Current password is incorrect");
    }
    repository.save(
        user.id(), user.username(), passwords.encode(newPassword), user.role(), user.enabled());
    repository.deleteSessionsExcept(user.id(), tokenHash);
  }

  @Transactional(readOnly = true)
  public List<StudioUserDTO> list() {
    return repository.list().stream().map(this::view).toList();
  }

  @Transactional
  public StudioUserDTO create(
      @Nullable String username, @Nullable String password, @Nullable StudioRole role) {
    validateUsername(username);
    validatePassword(password);
    if (role == null) {
      throw DomainException.validationReason("INVALID_ARGUMENT", "Role is required");
    }
    repository.lockAdminMutations();
    try {
      repository.create(UUID.randomUUID(), username, passwords.encode(password), role);
    } catch (DuplicateKeyException ex) {
      throw DomainException.conflict("USERNAME_EXISTS", "Username already exists");
    }
    return view(repository.byUsername(username).orElseThrow());
  }

  @Transactional
  public StudioUserDTO update(
      UUID id,
      @Nullable String username,
      @Nullable StudioRole role,
      @Nullable String password,
      @Nullable Boolean enabled) {
    if (username == null && role == null && password == null && enabled == null) {
      throw DomainException.validationReason(
          "INVALID_ARGUMENT", "At least one user change is required");
    }
    if (username != null) {
      validateUsername(username);
    }
    if (password != null) {
      validatePassword(password);
    }
    repository.lockAdminMutations();
    var old = repository.lockById(id).orElseThrow(() -> DomainException.notFound("user"));
    boolean removesAdmin =
        old.enabled()
            && old.role() == StudioRole.ADMIN
            && (role == StudioRole.USER || Boolean.FALSE.equals(enabled));
    if (removesAdmin && repository.countEnabledAdmins() <= 1) {
      throw DomainException.conflict(
          "LAST_ADMIN", "At least one enabled administrator is required");
    }
    String hash = password == null ? null : passwords.encode(password);
    try {
      repository.save(
          id,
          username == null ? old.username() : username,
          hash,
          role == null ? old.role() : role,
          enabled == null ? old.enabled() : enabled);
    } catch (DuplicateKeyException ex) {
      throw DomainException.conflict("USERNAME_EXISTS", "Username already exists");
    }
    if (hash != null || Boolean.FALSE.equals(enabled)) {
      repository.deleteSessions(id);
    }
    if (old.enabled() && Boolean.FALSE.equals(enabled)) {
      scheduleSuspensions.forEach(suspension -> suspension.suspend(id));
    }
    return view(repository.byId(id).orElseThrow());
  }

  private StudioUserDTO view(StudioAccountRepository.User u) {
    return new StudioUserDTO(u.id(), u.username(), u.role(), u.enabled(), u.createdAt());
  }

  private static DomainException invalidCredentials() {
    return DomainException.unauthorized("INVALID_CREDENTIALS", "Invalid username or password");
  }

  private static void validateUsername(@Nullable String value) {
    if (value == null || !USERNAME.matcher(value).matches()) {
      throw DomainException.validationReason("INVALID_ARGUMENT", "Invalid username");
    }
  }

  private static void validatePassword(@Nullable String value) {
    if (value == null || value.isBlank() || value.length() < 8 || value.length() > 128) {
      throw DomainException.validationReason(
          "INVALID_ARGUMENT", "Password must contain 8 to 128 characters and cannot be blank");
    }
  }

  private static byte[] hash(byte[] token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

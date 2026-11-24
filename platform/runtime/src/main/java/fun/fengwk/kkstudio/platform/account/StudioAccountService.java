package fun.fengwk.kkstudio.platform.account;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainException;

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
  public record Login(String token, AccountView user) {}

  private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,31}$");
  private final StudioAccountRepository repository;
  private final PasswordHasher passwords = new PasswordHasher();
  private final SecureRandom random = new SecureRandom();
  private final String dummyPasswordHash = passwords.encode("dummy-password");

  public StudioAccountService(StudioAccountRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public Login login(String username, String password) {
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
        new AccountView(user.id(), user.username(), user.role(), repository.profile(user.id())));
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
  public AccountView me(StudioPrincipal principal) {
    var user =
        repository.byId(principal.userId()).orElseThrow(() -> DomainException.notFound("user"));
    return new AccountView(user.id(), user.username(), user.role(), repository.profile(user.id()));
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
      throw DomainException.validationReason("WRONG_PASSWORD", "当前密码错误");
    }
    repository.save(
        user.id(), user.username(), passwords.encode(newPassword), user.role(), user.enabled());
    repository.deleteSessionsExcept(user.id(), tokenHash);
  }

  @Transactional(readOnly = true)
  public List<UserView> list(StudioPrincipal principal) {
    requireAdmin(principal);
    return repository.list().stream().map(this::view).toList();
  }

  @Transactional
  public UserView create(
      StudioPrincipal principal, String username, String password, StudioRole role) {
    requireAdmin(principal);
    repository.lockAdminMutations();
    validateUsername(username);
    validatePassword(password);
    validateRole(role);
    try {
      repository.create(UUID.randomUUID(), username, passwords.encode(password), role);
    } catch (DuplicateKeyException ex) {
      throw DomainException.conflict("USERNAME_EXISTS", "用户名已存在");
    }
    return view(repository.byUsername(username).orElseThrow());
  }

  @Transactional
  public UserView update(
      StudioPrincipal principal,
      UUID id,
      String username,
      StudioRole role,
      String password,
      Boolean enabled) {
    requireAdmin(principal);
    repository.lockAdminMutations();
    var old = repository.lockById(id).orElseThrow(() -> DomainException.notFound("user"));
    validateUsername(username == null ? old.username() : username);
    boolean removesAdmin =
        old.enabled()
            && old.role() == StudioRole.ADMIN
            && (role == StudioRole.USER || Boolean.FALSE.equals(enabled));
    if (removesAdmin && repository.countEnabledAdmins() <= 1) {
      throw DomainException.conflict("LAST_ADMIN", "至少需要一名启用的管理员");
    }
    String hash = null;
    if (password != null) {
      validatePassword(password);
      hash = passwords.encode(password);
    }
    try {
      repository.save(
          id,
          username == null ? old.username() : username,
          hash,
          role == null ? old.role() : role,
          enabled == null ? old.enabled() : enabled);
    } catch (DuplicateKeyException ex) {
      throw DomainException.conflict("USERNAME_EXISTS", "用户名已存在");
    }
    if (hash != null || Boolean.FALSE.equals(enabled)) {
      repository.deleteSessions(id);
    }
    return view(repository.byId(id).orElseThrow());
  }

  private UserView view(StudioAccountRepository.User u) {
    return new UserView(u.id(), u.username(), u.role(), u.enabled(), u.createdAt());
  }

  private static void requireAdmin(StudioPrincipal p) {
    if (p.role() != StudioRole.ADMIN) {
      throw DomainException.forbidden("ADMIN_REQUIRED", "需要管理员权限");
    }
  }

  private static DomainException invalidCredentials() {
    return DomainException.unauthorized("INVALID_CREDENTIALS", "用户名或密码错误");
  }

  private static void validateRole(StudioRole role) {
    if (role == null) {
      throw DomainException.validation("role", "角色无效");
    }
  }

  private static void validateUsername(String value) {
    if (value == null || !USERNAME.matcher(value).matches()) {
      throw DomainException.validationReason("INVALID_ARGUMENT", "用户名格式无效");
    }
  }

  private static void validatePassword(String value) {
    if (value == null || value.length() < 8 || value.length() > 128) {
      throw DomainException.validationReason("INVALID_ARGUMENT", "密码长度必须为 8–128 个字符");
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

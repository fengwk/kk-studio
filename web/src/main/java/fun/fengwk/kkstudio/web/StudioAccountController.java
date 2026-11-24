package fun.fengwk.kkstudio.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import fun.fengwk.kkstudio.platform.account.AccountView;
import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.account.UserView;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
public class StudioAccountController {
  public record LoginRequest(String username, String password) {}

  public record PasswordRequest(String currentPassword, String newPassword) {}

  public record CreateUserRequest(String username, String password, StudioRole role) {}

  public record UpdateUserRequest(
      @Nullable String username,
      @Nullable StudioRole role,
      @Nullable String password,
      @Nullable Boolean enabled) {}

  private final StudioAccountService accounts;

  public StudioAccountController(StudioAccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping("/api/auth/me")
  AccountView me(StudioPrincipal principal) {
    return accounts.me(principal);
  }

  @PostMapping("/api/auth/login")
  ResponseEntity<AccountView> login(@RequestBody LoginRequest body, HttpServletRequest request) {
    var login = accounts.login(body.username(), body.password());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie(request, login.token(), Duration.ofDays(30)))
        .body(login.user());
  }

  @PostMapping("/api/auth/logout")
  ResponseEntity<Void> logout(HttpServletRequest request) {
    accounts.logout(StudioBrowserSession.token(request));
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO))
        .build();
  }

  @PutMapping("/api/auth/password")
  ResponseEntity<Void> password(
      StudioPrincipal principal, @RequestBody PasswordRequest body, HttpServletRequest request) {
    accounts.changePassword(
        principal, StudioBrowserSession.token(request), body.currentPassword(), body.newPassword());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/users")
  List<UserView> users(StudioPrincipal principal) {
    return accounts.list(principal);
  }

  @PostMapping("/api/users")
  UserView create(StudioPrincipal principal, @RequestBody CreateUserRequest body) {
    return accounts.create(principal, body.username(), body.password(), body.role());
  }

  @PatchMapping("/api/users/{id}")
  UserView update(
      StudioPrincipal principal, @PathVariable UUID id, @RequestBody UpdateUserRequest body) {
    return accounts.update(
        principal, id, body.username(), body.role(), body.password(), body.enabled());
  }

  private static String cookie(HttpServletRequest request, String token, Duration age) {
    return ResponseCookie.from(StudioBrowserSession.COOKIE, token)
        .httpOnly(true)
        .secure(request.isSecure())
        .sameSite("Strict")
        .path("/")
        .maxAge(age)
        .build()
        .toString();
  }
}

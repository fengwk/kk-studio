package fun.fengwk.kkstudio.platform.account.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.dto.CreateStudioUserRequestDTO;
import fun.fengwk.kkstudio.platform.account.dto.StudioAccountDTO;
import fun.fengwk.kkstudio.platform.account.dto.StudioLoginRequestDTO;
import fun.fengwk.kkstudio.platform.account.dto.StudioPasswordRequestDTO;
import fun.fengwk.kkstudio.platform.account.dto.StudioUserDTO;
import fun.fengwk.kkstudio.platform.account.dto.UpdateStudioUserRequestDTO;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
public class StudioAccountController {
  private final StudioAccountService accounts;

  public StudioAccountController(StudioAccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping("/api/auth/me")
  public StudioAccountDTO me(StudioPrincipal principal) {
    return accounts.me(principal);
  }

  @PostMapping("/api/auth/login")
  public ResponseEntity<StudioAccountDTO> login(
      @RequestBody(required = false) StudioLoginRequestDTO body, HttpServletRequest request) {
    var login =
        accounts.login(
            body == null ? null : body.username(), body == null ? null : body.password());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie(request, login.token(), Duration.ofDays(30)))
        .body(login.user());
  }

  @PostMapping("/api/auth/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    accounts.logout(StudioBrowserSession.token(request));
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO))
        .build();
  }

  @PutMapping("/api/auth/password")
  public ResponseEntity<Void> password(
      StudioPrincipal principal,
      @RequestBody StudioPasswordRequestDTO body,
      HttpServletRequest request) {
    accounts.changePassword(
        principal, StudioBrowserSession.token(request), body.currentPassword(), body.newPassword());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/users")
  public List<StudioUserDTO> users() {
    return accounts.list();
  }

  @PostMapping("/api/users")
  public StudioUserDTO create(@RequestBody CreateStudioUserRequestDTO body) {
    return accounts.create(body.username(), body.password(), body.role());
  }

  @PatchMapping("/api/users/{id}")
  public StudioUserDTO update(@PathVariable UUID id, @RequestBody UpdateStudioUserRequestDTO body) {
    return accounts.update(
        id, body.getUsername(), body.getRole(), body.getPassword(), body.getEnabled());
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

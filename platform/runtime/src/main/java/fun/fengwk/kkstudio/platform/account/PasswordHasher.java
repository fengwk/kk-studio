package fun.fengwk.kkstudio.platform.account;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

final class PasswordHasher {
  private final PasswordEncoder encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

  String encode(String password) {
    return "{pbkdf2}" + encoder.encode(password);
  }

  boolean matches(String password, String hash) {
    return hash != null
        && hash.startsWith("{pbkdf2}")
        && encoder.matches(password, hash.substring("{pbkdf2}".length()));
  }
}

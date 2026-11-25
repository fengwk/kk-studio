package fun.fengwk.kkstudio.platform.account;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

public final class StudioBrowserSession {
  public static final String COOKIE = "studio_session";
  public static final String PRINCIPAL_ATTRIBUTE = "studioPrincipal";

  private StudioBrowserSession() {}

  public static String token(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if (COOKIE.equals(cookie.getName())) {
          return cookie.getValue();
        }
      }
    }
    return null;
  }
}

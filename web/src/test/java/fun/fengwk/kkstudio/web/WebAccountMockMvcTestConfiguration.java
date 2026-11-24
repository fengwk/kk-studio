package fun.fengwk.kkstudio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;

import java.util.Arrays;

@TestConfiguration(proxyBeanMethods = false)
class WebAccountMockMvcTestConfiguration {
  private static final String ORIGIN = "http://localhost";

  @Bean
  AdminCredentials adminCredentials() {
    return new AdminCredentials();
  }

  @Bean
  MockMvcBuilderCustomizer accountDefaultRequest(AdminCredentials credentials) {
    RequestPostProcessor processor =
        request -> {
          String path = request.getRequestURI();
          if (path.equals("/api") || path.startsWith("/api/")) {
            if (request.getCookies() == null
                || Arrays.stream(request.getCookies())
                    .noneMatch(cookie -> StudioBrowserSession.COOKIE.equals(cookie.getName()))) {
              request.setCookies(new Cookie(StudioBrowserSession.COOKIE, credentials.token()));
            }
            if (request.getHeader("Origin") == null) {
              request.addHeader("Origin", ORIGIN);
            }
          }
          return request;
        };
    return builder -> builder.defaultRequest(get("/").with(processor));
  }

  static final class AdminCredentials {
    private volatile String token;

    void setToken(String token) {
      this.token = token;
    }

    String token() {
      if (token == null) {
        throw new IllegalStateException("admin session has not been initialized for this test");
      }
      return token;
    }
  }
}

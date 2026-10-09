package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.net.URI;

/** 意图：官方发布传输只接受 HTTPS 官方 GitHub 主机，其它方案/主机（含形似 githubusercontent 的伪造域）必须被拒绝， 避免受管更新被重定向到任意地址。 */
class OfficialReleaseHttpTest {

  @Test
  void allowsOfficialHttpsHosts() {
    assertTrue(OfficialReleaseHttp.isAllowed(URI.create("https://github.com/fengwk/kk-studio")));
    assertTrue(
        OfficialReleaseHttp.isAllowed(
            URI.create("https://objects.githubusercontent.com/some/path")));
    assertTrue(OfficialReleaseHttp.isAllowed(URI.create("https://api.github.com/repos")));
  }

  @Test
  void rejectsNonOfficialOrInsecureTargets() {
    assertFalse(OfficialReleaseHttp.isAllowed(URI.create("http://github.com/fengwk/kk-studio")));
    assertFalse(OfficialReleaseHttp.isAllowed(URI.create("https://evil.example/kk-studio.jar")));
    assertFalse(OfficialReleaseHttp.isAllowed(URI.create("https://githubusercontent.com.evil.io")));
    assertFalse(OfficialReleaseHttp.isAllowed(URI.create("https://notgithub.com")));
    assertFalse(OfficialReleaseHttp.isAllowed(URI.create("file:///etc/passwd")));
    assertFalse(OfficialReleaseHttp.isAllowed(URI.create("https:///no-host")));
  }

  @Test
  void parseAllowedThrowsForDisallowedUrl() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OfficialReleaseHttp.parseAllowed("https://evil.example/kk-studio.jar"));
  }
}

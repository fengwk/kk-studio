package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/** 全局代理保存边界：公共解析器严格校验，字段错误不包含原输入或敏感 cause。 */
class SystemSettingsNetworkTest {

  @Test
  void acceptsDirectAndUnauthenticatedHttpProxyWithEmptyBypass() {
    // null 地址明确直连；空 bypass 是合法且必须保留的用户选择。
    SystemSettings.Network direct = new SystemSettings.Network(null, "");
    assertNull(direct.proxyUrl());
    assertEquals("", direct.noProxyHosts());
    for (String url : List.of("http://proxy:1", "http://127.0.0.1:65535", "http://[::1]:3128")) {
      assertEquals(url, new SystemSettings.Network(url, "").proxyUrl());
    }
    assertDoesNotThrow(
        () -> new SystemSettings.Network("http://proxy:3128", "localhost,127.*,::1"));
  }

  /** 意图：Backend 系统设置接受与 Daemon 相同的 IPv4/IPv6 CIDR 语法，非法规则报精确字段错误。 */
  @Test
  void acceptsCidrBypassAndRejectsMalformedWithFieldError() {
    SystemSettings.Network network =
        new SystemSettings.Network(
            "http://proxy:3128", "localhost,192.168.0.0/16,100.64.0.0/10,2001:db8::/32");
    assertEquals("localhost,192.168.0.0/16,100.64.0.0/10,2001:db8::/32", network.noProxyHosts());
    assertDoesNotThrow(() -> new SystemSettings.Network(null, "0.0.0.0/0,::/0"));
    for (String bypass : List.of("192.168.0.0/33", "10.0.0.0/", "host/8", "2001:db8::/129")) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class, () -> new SystemSettings.Network(null, bypass));
      assertEquals("network.noProxyHosts is invalid", error.getMessage());
      assertNull(error.getCause());
    }
  }

  @Test
  void rejectsInvalidProxyUrlsWithoutEchoingInputOrCause() {
    // 完整 URL + 显式端口；认证、TLS-to-proxy、SOCKS、path/query/fragment 一律 fail-closed。
    for (String url :
        List.of(
            "",
            "proxy:3128",
            "http://proxy",
            "http://proxy:0",
            "http://proxy:65536",
            "https://proxy:3128",
            "socks5://proxy:1080",
            "http://synthetic-user:synthetic-password@proxy:3128",
            "http://proxy:3128/",
            "http://proxy:3128/path",
            "http://proxy:3128?secret=synthetic",
            "http://proxy:3128#fragment")) {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> new SystemSettings.Network(url, ""));
      assertEquals("network.proxyUrl is invalid", error.getMessage());
      assertNull(error.getCause());
    }
  }

  @Test
  void enforcesTextBoundsAndRequiredBypassWithPreciseFields() {
    // 长度边界不 trim，也不把空串/缺失折叠成默认值。
    String longestUrl = "http://" + "a".repeat(2039) + ":1";
    assertEquals(2048, longestUrl.length());
    assertDoesNotThrow(() -> new SystemSettings.Network(longestUrl, ""));
    assertEquals(
        "network.proxyUrl must not exceed 2048 characters",
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemSettings.Network(longestUrl + "0", ""))
            .getMessage());
    assertDoesNotThrow(() -> new SystemSettings.Network(null, "a".repeat(4096)));
    assertEquals(
        "network.noProxyHosts must not exceed 4096 characters",
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemSettings.Network(null, "a".repeat(4097)))
            .getMessage());
    assertEquals(
        "network.noProxyHosts is required",
        assertThrows(IllegalArgumentException.class, () -> new SystemSettings.Network(null, null))
            .getMessage());
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SystemSettings.Network("http://proxy:3128", "http://invalid/path"));
    assertEquals("network.noProxyHosts is invalid", error.getMessage());
    assertNull(error.getCause());
  }
}

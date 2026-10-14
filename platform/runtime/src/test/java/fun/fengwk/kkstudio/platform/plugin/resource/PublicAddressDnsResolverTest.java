package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** 地址准入到 HTTP 客户端 DNS 解析点的适配单元测试。 */
class PublicAddressDnsResolverTest {

  /** 解析结果与策略完全一致：校验通过的整批地址就是回给客户端用于建连的地址。 */
  @Test
  void resolveReturnsTheValidatedAddresses() throws UnknownHostException {
    InetAddress first = InetAddress.getByName("93.184.216.34");
    InetAddress second = InetAddress.getByName("2606:4700:4700::1111");
    PublicAddressDnsResolver resolver =
        new PublicAddressDnsResolver(new PublicAddressPolicy(host -> List.of(first, second)));

    assertArrayEquals(new InetAddress[] {first, second}, resolver.resolve("media.example.com"));
  }

  /** 解析到非公网地址时必须在返回任何地址前确定性失败，客户端拿不到可连接的目标。 */
  @Test
  void resolveRejectsNonPublicAddresses() throws UnknownHostException {
    InetAddress metadata = InetAddress.getByName("169.254.169.254");
    PublicAddressDnsResolver resolver =
        new PublicAddressDnsResolver(new PublicAddressPolicy(host -> List.of(metadata)));

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class, () -> resolver.resolve("media.example.com"));
    assertTrue(exception.getMessage().contains("non-public address"));
  }

  /** 规范主机名解析不在媒体下载路径上，直接回退为主机名本身，绝不绕过地址准入再做系统解析。 */
  @Test
  void resolveCanonicalHostnameDoesNotResolveAgain() {
    PublicAddressDnsResolver resolver =
        new PublicAddressDnsResolver(
            new PublicAddressPolicy(
                host -> {
                  throw new AssertionError("must not resolve");
                }));

    assertEquals("media.example.com", resolver.resolveCanonicalHostname("media.example.com"));
  }
}

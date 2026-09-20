package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** 远端媒体地址准入策略单元测试。 */
class PublicAddressPolicyTest {

  /** IPv4 私有、环回、链路本地、保留及测试网段地址必须被判定为非公网地址。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "10.0.0.1",
        "172.16.0.1",
        "172.31.255.255",
        "192.168.1.1",
        "127.0.0.1",
        "169.254.1.1",
        "100.64.0.1",
        "0.0.0.0",
        "240.0.0.1",
        "255.255.255.255",
        "224.0.0.1",
        "192.0.0.1",
        "198.18.0.1",
        "203.0.113.5"
      })
  void rejectsNonPublicIpv4Addresses(String ip) throws UnknownHostException {
    InetAddress address = InetAddress.getByName(ip);
    assertFalse(
        PublicAddressPolicy.isPublicAddress(address),
        () -> "Address should be rejected as non-public: " + ip);
  }

  /** IPv6 环回、链路本地、唯一本地(ULA)、组播、文档段及过渡隧道前缀必须被判定为非公网地址。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "::",
        "::1",
        "fe80::1",
        "fec0::1",
        "fc00::1",
        "fd12:3456::1",
        "ff02::1",
        "2001:db8::1",
        "2001::1",
        "2002::1",
        "::ffff:10.0.0.1"
      })
  void rejectsNonPublicIpv6Addresses(String ip) throws UnknownHostException {
    InetAddress address = InetAddress.getByName(ip);
    assertFalse(
        PublicAddressPolicy.isPublicAddress(address),
        () -> "Address should be rejected as non-public: " + ip);
  }

  /** NAT64 翻译前缀（RFC 6052 Well-Known 与 RFC 8215 Local-Use）必须整体拒绝，否则可借翻译指向任意 IPv4。 */
  @ParameterizedTest
  @ValueSource(strings = {"64:ff9b::1", "64:ff9b::a00:1", "64:ff9b:1::1", "64:ff9b:1:2::3"})
  void rejectsNat64Prefixes(String ip) throws UnknownHostException {
    InetAddress address = InetAddress.getByName(ip);
    assertFalse(
        PublicAddressPolicy.isPublicAddress(address),
        () -> "NAT64 address should be rejected as non-public: " + ip);
  }

  /** 未被内嵌 IPv4 覆盖的 00xx::/8 保留地址同样不得放行。 */
  @ParameterizedTest
  @ValueSource(strings = {"::2:0:0", "::1:0:0"})
  void rejectsReservedZeroPrefixIpv6Addresses(String ip) throws UnknownHostException {
    InetAddress address = InetAddress.getByName(ip);
    assertFalse(
        PublicAddressPolicy.isPublicAddress(address),
        () -> "Reserved ::/8 address should be rejected as non-public: " + ip);
  }

  /** 公网可路由的 IPv4 和 IPv6 地址必须被允许放行。 */
  @ParameterizedTest
  @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "2606:4700:4700::1111"})
  void allowsPublicAddresses(String ip) throws UnknownHostException {
    InetAddress address = InetAddress.getByName(ip);
    assertTrue(
        PublicAddressPolicy.isPublicAddress(address),
        () -> "Public address should be accepted: " + ip);
  }

  /** 解析结果全部为公网地址时，校验必须顺利通过。 */
  @Test
  void assertPublicHostAllowsAllPublicAddresses() {
    HostResolver resolver =
        host ->
            List.of(
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("2606:4700:4700::1111"));
    PublicAddressPolicy policy = new PublicAddressPolicy(resolver);

    assertDoesNotThrow(() -> policy.assertPublicHost("example.com"));
  }

  /** 当主机解析结果包含多条记录且存在公网与私网混合时，必须整体拒绝，防止多 A 记录绕过。 */
  @Test
  void assertPublicHostRejectsMixedPublicAndPrivateAddresses() {
    HostResolver resolver =
        host -> List.of(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.1"));
    PublicAddressPolicy policy = new PublicAddressPolicy(resolver);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> policy.assertPublicHost("mixed.example.com"));
    assertTrue(exception.getMessage().contains("non-public address"));
  }

  /** 当 DNS 解析失败（抛出 UnknownHostException）时，必须收敛为 PluginResourceUnavailableException。 */
  @Test
  void assertPublicHostFailsWhenResolutionThrowsUnknownHostException() {
    HostResolver resolver =
        host -> {
          throw new UnknownHostException("Host not found: " + host);
        };
    PublicAddressPolicy policy = new PublicAddressPolicy(resolver);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> policy.assertPublicHost("unresolvable.example.com"));
    assertTrue(exception.getMessage().contains("cannot be resolved"));
    assertTrue(exception.getCause() instanceof UnknownHostException);
  }

  /** 当 DNS 解析返回空列表或 null 时，必须确定性失败。 */
  @Test
  void assertPublicHostFailsWhenResolutionReturnsEmpty() {
    PublicAddressPolicy policyWithEmptyList = new PublicAddressPolicy(host -> List.of());
    PluginResourceUnavailableException emptyException =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> policyWithEmptyList.assertPublicHost("empty.example.com"));
    assertTrue(emptyException.getMessage().contains("cannot be resolved"));

    PublicAddressPolicy policyWithNull = new PublicAddressPolicy(host -> null);
    PluginResourceUnavailableException nullException =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> policyWithNull.assertPublicHost("null.example.com"));
    assertTrue(nullException.getMessage().contains("cannot be resolved"));
  }

  /** 当传入的 host 为 null、空字符串或仅包含空白字符时，必须在解析前直接失败。 */
  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t\n"})
  void assertPublicHostRejectsBlankHost(String host) {
    PublicAddressPolicy policy = new PublicAddressPolicy(h -> List.of());

    PluginResourceUnavailableException exception =
        assertThrows(PluginResourceUnavailableException.class, () -> policy.assertPublicHost(host));
    assertTrue(exception.getMessage().contains("host is required"));
  }

  /** 当传入的 host 为 null 时，必须在解析前直接失败。 */
  @Test
  void assertPublicHostRejectsNullHost() {
    PublicAddressPolicy policy = new PublicAddressPolicy(h -> List.of());

    PluginResourceUnavailableException exception =
        assertThrows(PluginResourceUnavailableException.class, () -> policy.assertPublicHost(null));
    assertTrue(exception.getMessage().contains("host is required"));
  }
}

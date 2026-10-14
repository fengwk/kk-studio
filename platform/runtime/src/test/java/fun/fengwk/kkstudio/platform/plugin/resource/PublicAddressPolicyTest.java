package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

  /** 解析结果全部为公网地址时校验通过，并且返回的就是那批被校验过的地址（调用方必须直接用它建连）。 */
  @Test
  void resolvePublicHostReturnsTheValidatedAddresses() throws UnknownHostException {
    InetAddress ipv4 = InetAddress.getByName("93.184.216.34");
    InetAddress ipv6 = InetAddress.getByName("2606:4700:4700::1111");
    PublicAddressPolicy policy = new PublicAddressPolicy(host -> List.of(ipv4, ipv6));

    InetAddress[] resolved = policy.resolvePublicHost("example.com");

    assertArrayEquals(new InetAddress[] {ipv4, ipv6}, resolved);
  }

  /** 当主机解析结果包含多条记录且存在公网与私网混合时，必须整体拒绝，防止多 A 记录绕过。 */
  @Test
  void resolvePublicHostRejectsMixedPublicAndPrivateAddresses() {
    HostResolver resolver =
        host -> List.of(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.1"));
    PublicAddressPolicy policy = new PublicAddressPolicy(resolver);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> policy.resolvePublicHost("mixed.example.com"));
    assertTrue(exception.getMessage().contains("non-public address"));
  }

  /** 当 DNS 解析失败（抛出 UnknownHostException）时，必须收敛为 PluginResourceUnavailableException。 */
  @Test
  void resolvePublicHostFailsWhenResolutionThrowsUnknownHostException() {
    HostResolver resolver =
        host -> {
          throw new UnknownHostException("Host not found: " + host);
        };
    PublicAddressPolicy policy = new PublicAddressPolicy(resolver);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> policy.resolvePublicHost("unresolvable.example.com"));
    assertTrue(exception.getMessage().contains("cannot be resolved"));
    assertTrue(exception.getCause() instanceof UnknownHostException);
  }

  /** 当 DNS 解析返回空列表或 null 时，必须确定性失败。 */
  @Test
  void resolvePublicHostFailsWhenResolutionReturnsEmpty() {
    PublicAddressPolicy policyWithEmptyList = new PublicAddressPolicy(host -> List.of());
    PluginResourceUnavailableException emptyException =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> policyWithEmptyList.resolvePublicHost("empty.example.com"));
    assertTrue(emptyException.getMessage().contains("cannot be resolved"));

    PublicAddressPolicy policyWithNull = new PublicAddressPolicy(host -> null);
    PluginResourceUnavailableException nullException =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> policyWithNull.resolvePublicHost("null.example.com"));
    assertTrue(nullException.getMessage().contains("cannot be resolved"));
  }

  /** 当传入的 host 为 null、空字符串或仅包含空白字符时，必须在解析前直接失败。 */
  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t\n"})
  void resolvePublicHostRejectsBlankHost(String host) {
    PublicAddressPolicy policy = new PublicAddressPolicy(h -> List.of());

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class, () -> policy.resolvePublicHost(host));
    assertTrue(exception.getMessage().contains("host is required"));
  }

  /** 当传入的 host 为 null 时，必须在解析前直接失败。 */
  @Test
  void resolvePublicHostRejectsNullHost() {
    PublicAddressPolicy policy = new PublicAddressPolicy(h -> List.of());

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class, () -> policy.resolvePublicHost(null));
    assertTrue(exception.getMessage().contains("host is required"));
  }

  /** null 地址不得抛异常，且必须判定为非公网。 */
  @Test
  void rejectsNullAddress() {
    assertFalse(PublicAddressPolicy.isPublicAddress(null));
  }

  /**
   * IPv4 段级判定必须显式拒绝全部保留范围。
   *
   * <p>直接按字面量字节判定：其中 169.254/16、RFC 1918 等会被 {@link InetAddress#isLinkLocalAddress()} 之类的快捷判定先拦下，
   * 只有直接锁定段级判定才能防止后续简化误删这些范围。
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "0.0.0.1",
        "127.0.0.1",
        "240.0.0.1",
        "255.255.255.255",
        "10.0.0.1",
        "172.16.0.1",
        "172.31.1.1",
        "192.168.1.1",
        "169.254.169.254",
        "100.64.0.1",
        "192.0.0.1",
        "192.0.2.5",
        "198.18.0.1",
        "198.19.1.1",
        "198.51.100.7",
        "203.0.113.9"
      })
  void rejectsNonPublicIpv4ByteRanges(String ip) throws UnknownHostException {
    assertFalse(
        PublicAddressPolicy.isPublicIpv4(InetAddress.getByName(ip).getAddress()),
        () -> "IPv4 range must be rejected: " + ip);
  }

  /** 公网 IPv4 字面量必须按字节判定放行。 */
  @ParameterizedTest
  @ValueSource(strings = {"1.1.1.1", "8.8.8.8", "93.184.216.34", "203.0.114.9"})
  void allowsPublicIpv4ByteRanges(String ip) throws UnknownHostException {
    assertTrue(
        PublicAddressPolicy.isPublicIpv4(InetAddress.getByName(ip).getAddress()),
        () -> "IPv4 address must be accepted: " + ip);
  }

  /**
   * IPv6 段级判定必须显式拒绝 JDK 不覆盖或语义不同的保留范围。
   *
   * <p>fe80::/10 会被 {@code isLinkLocalAddress()}、ff00::/8 会被 {@code isMulticastAddress()} 先拦下，
   * 因此这些段同样需要直接锁定，避免「快捷判定看起来已经覆盖」而删掉显式范围。
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "fe80::1",
        "fec0::1",
        "fc00::1",
        "fd12:3456::1",
        "ff02::1",
        "ff05::2",
        "2001:db8::1",
        "2001::1",
        "2002::1",
        "64:ff9b::1",
        "64:ff9b:1:2::3",
        "::1:0:0",
        "::2:0:0"
      })
  void rejectsNonPublicIpv6ByteRanges(String ip) throws UnknownHostException {
    assertFalse(
        PublicAddressPolicy.isPublicIpv6(InetAddress.getByName(ip).getAddress()),
        () -> "IPv6 range must be rejected: " + ip);
  }

  /** 公网 IPv6 字面量必须放行。 */
  @ParameterizedTest
  @ValueSource(strings = {"2606:4700:4700::1111", "2400:3200::1"})
  void allowsPublicIpv6ByteRanges(String ip) throws UnknownHostException {
    assertTrue(
        PublicAddressPolicy.isPublicIpv6(InetAddress.getByName(ip).getAddress()),
        () -> "IPv6 address must be accepted: " + ip);
  }

  /**
   * 内嵌 IPv4 的 IPv6 形式必须按内嵌 IPv4 判定（IPv4-mapped 与已废弃的 IPv4-compatible 两路）。
   *
   * <p>JDK 会把这类字面量解析成 {@link java.net.Inet4Address}，所以只能用原始字节锁定这条 SSRF 关键路径。
   */
  @Test
  void judgesEmbeddedIpv4ByIpv4Rules() throws UnknownHostException {
    assertFalse(PublicAddressPolicy.isPublicIpv6(embeddedIpv4Bytes(true, "10.0.0.1")));
    assertFalse(PublicAddressPolicy.isPublicIpv6(embeddedIpv4Bytes(false, "169.254.169.254")));
    assertTrue(PublicAddressPolicy.isPublicIpv6(embeddedIpv4Bytes(true, "93.184.216.34")));
    assertTrue(PublicAddressPolicy.isPublicIpv6(embeddedIpv4Bytes(false, "8.8.8.8")));
  }

  /** 按映射/兼容两种前缀构造 16 字节 IPv6 字面量，低 32 位放给定的 IPv4 地址。 */
  private static byte[] embeddedIpv4Bytes(boolean mapped, String ipv4) throws UnknownHostException {
    byte[] bytes = new byte[16];
    bytes[10] = mapped ? (byte) 0xFF : 0;
    bytes[11] = mapped ? (byte) 0xFF : 0;
    byte[] address = InetAddress.getByName(ipv4).getAddress();
    System.arraycopy(address, 0, bytes, 12, address.length);
    return bytes;
  }
}

package fun.fengwk.kkstudio.platform.plugin.resource;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

/**
 * 远端媒体下载的地址准入策略：解析出的**每一个**地址都必须是公网地址。
 *
 * <p>只检查第一个地址或只检查字面量都是已知的 SSRF 缺口，因此这里逐地址判定，并且在任一地址落在非公网范围时整体拒绝（不允许「多条 A 记录里有一条公网」就放行，
 * 否则后续连接可能落到私有地址）。IPv4 与 IPv6 的保留/私有/链路本地/组播范围都必须显式覆盖：JDK 的 {@code isSiteLocalAddress()} 对 IPv6
 * ULA（{@code fc00::/7}）与 IPv6 文档段返回 false，因此这些范围由本类自己判定。
 *
 * <p>本策略不做 DNS pinning：连接仍由 HTTP 客户端按主机名建立，因此理论上存在解析结果变化的窗口。这个残余风险被两件事限制：实现固定使用 {@code
 * Redirect.NEVER}，且每个响应体都受字节与期限预算约束，不会因为一次重定向就访问到未校验的地址。
 */
final class PublicAddressPolicy {

  private final HostResolver resolver;

  PublicAddressPolicy(HostResolver resolver) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  /** 校验主机名解析出的全部地址都是公网地址；任一条不满足即抛出确定性失败。 */
  void assertPublicHost(String host) {
    if (host == null || host.isBlank()) {
      throw new PluginResourceUnavailableException("remote media host is required");
    }
    List<InetAddress> addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException error) {
      throw new PluginResourceUnavailableException("remote media host cannot be resolved", error);
    }
    if (addresses == null || addresses.isEmpty()) {
      throw new PluginResourceUnavailableException("remote media host cannot be resolved");
    }
    for (InetAddress address : addresses) {
      if (!isPublicAddress(address)) {
        throw new PluginResourceUnavailableException(
            "remote media host resolves to a non-public address");
      }
    }
  }

  /** 地址是否属于公网可路由范围。 */
  static boolean isPublicAddress(InetAddress address) {
    if (address == null) {
      return false;
    }
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    if (address instanceof Inet4Address v4) {
      return isPublicIpv4(v4.getAddress());
    }
    if (address instanceof Inet6Address v6) {
      return isPublicIpv6(v6.getAddress());
    }
    return false;
  }

  private static boolean isPublicIpv4(byte[] bytes) {
    int first = bytes[0] & 0xFF;
    int second = bytes[1] & 0xFF;
    int third = bytes[2] & 0xFF;
    if (first == 0 || first == 127 || first >= 240) {
      // 0/8 "本网络"、127/8 环回、240/4 保留（含 255.255.255.255 受限广播）。
      return false;
    }
    if (first == 10
        || (first == 192 && second == 168)
        || (first == 172 && second >= 16 && second <= 31)) {
      // RFC 1918 私有范围（isSiteLocalAddress 已覆盖，这里保留显式判定以便单独测试）。
      return false;
    }
    if (first == 169 && second == 254) {
      return false;
    }
    if (first == 100 && second >= 64 && second <= 127) {
      // RFC 6598 运营商级 NAT 共享地址。
      return false;
    }
    if (first == 192 && second == 0 && (third == 0 || third == 2)) {
      // 192.0.0.0/24 IETF 协议保留、192.0.2.0/24 TEST-NET-1。
      return false;
    }
    if (first == 198 && (second == 18 || second == 19)) {
      return false;
    }
    if (first == 198 && second == 51 && third == 100) {
      // 198.51.100.0/24 TEST-NET-2。
      return false;
    }
    return !(first == 203 && second == 0 && third == 113);
  }

  /** 该 IPv6 地址是否在低 32 位内嵌 IPv4 地址（IPv4-mapped 或已废弃的 IPv4-compatible）。 */
  private static boolean embedsIpv4(byte[] bytes) {
    for (int index = 0; index < 10; index++) {
      if (bytes[index] != 0) {
        return false;
      }
    }
    if (bytes[10] == 0 && bytes[11] == 0) {
      return true;
    }
    return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
  }

  private static boolean isPublicIpv6(byte[] bytes) {
    int first = bytes[0] & 0xFF;
    int second = bytes[1] & 0xFF;
    if (embedsIpv4(bytes)) {
      // ::/96（已废弃的 IPv4-compatible）与 ::ffff:0:0/96（IPv4-mapped）的语义就是 IPv4 地址，必须按 IPv4 规则判定，
      // 否则 ::ffff:10.0.0.1 这类被封装的内网地址会当作公网 IPv6 放行。
      return isPublicIpv4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
    }
    if (first == 0x00 && second == 0x64 && (bytes[2] & 0xFF) == 0xFF && (bytes[3] & 0xFF) == 0x9B) {
      // 0064:ff9b::/32：RFC 6052 Well-Known 与 RFC 8215 Local-Use NAT64 前缀，翻译后可指向任意 IPv4。
      return false;
    }
    if (first == 0x00) {
      // 其余 00xx::/8 首都为 0 的地址全部落在 IETF 保留空间。
      return false;
    }
    if ((first & 0xFE) == 0xFC) {
      // fc00::/7 唯一本地地址（ULA）：JDK 不把它视为 site-local，必须显式拒绝。
      return false;
    }
    if (first == 0x20 && second == 0x01 && (bytes[2] & 0xFF) == 0x0D && (bytes[3] & 0xFF) == 0xB8) {
      // 2001:db8::/32 文档段。
      return false;
    }
    if (first == 0x20 && second == 0x01 && (bytes[2] & 0xFF) == 0x00 && (bytes[3] & 0xFF) == 0x00) {
      // 2001::/32 Teredo：隧道端点可承载私有地址。
      return false;
    }
    if (first == 0x20 && second == 0x02) {
      // 2002::/16 6to4：同样可封装私有 IPv4。
      return false;
    }
    if ((first & 0xFE) == 0xFE && (second & 0xC0) == 0x80) {
      // fe80::/10 链路本地（isLinkLocalAddress 已覆盖，保留显式判定）。
      return false;
    }
    if ((first & 0xFF) == 0xFF) {
      return false;
    }
    return !(first == 0xFE && (second & 0xC0) == 0xC0);
  }
}

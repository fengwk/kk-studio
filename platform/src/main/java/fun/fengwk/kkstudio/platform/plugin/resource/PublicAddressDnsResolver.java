package fun.fengwk.kkstudio.platform.plugin.resource;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.util.Objects;

/**
 * 把 {@link PublicAddressPolicy} 接到 Apache HttpClient5 的 socket 解析点上。
 *
 * <p>HttpClient5 每次为新连接解析目标地址时都调用 {@link #resolve(String)}，并把返回的地址列表原样用于建连。因此这里既校验又返回同一批地址：
 * 校验通过的地址就是真正连上的地址，攻击者即使在建连前把 DNS 改到内网地址，也会在返回地址之前被策略拒绝，不会发生第二次系统解析造成的 TOCTOU。
 *
 * <p>URL 里的 IPv4/IPv6 字面量同样走这条路径：由请求 URI 推导出的 {@code HttpHost} 不携带已解析地址（{@code getAddress()} 为
 * null）， 客户端仍会调用本解析器，字面量因此也必须通过地址准入，传输层无需再做一次字面量检查。
 *
 * <p>TLS 不受影响：SNI 与证书主机名校验仍由客户端按原始主机名（{@code HttpHost}）执行，本类只替换地址解析。
 */
final class PublicAddressDnsResolver implements DnsResolver {

  private final PublicAddressPolicy policy;

  PublicAddressDnsResolver(PublicAddressPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  public InetAddress[] resolve(String host) {
    return policy.resolvePublicHost(host);
  }

  /** 规范主机名解析不参与地址准入，因此这里直接回退为主机名本身，绝不做第二次系统解析。HttpClient5 只在 GSS 认证场景调用本方法，媒体下载路径不会调用它。 */
  @Override
  public String resolveCanonicalHostname(String host) {
    return host;
  }
}

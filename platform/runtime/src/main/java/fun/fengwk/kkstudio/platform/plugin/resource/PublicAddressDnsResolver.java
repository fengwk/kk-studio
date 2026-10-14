package fun.fengwk.kkstudio.platform.plugin.resource;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.util.Objects;

/**
 * 把 {@link PublicAddressPolicy} 接到媒体路由阶段的目标地址解析点上。
 *
 * <p>{@link PinnedMediaRoutePlanner} 每次选路先调用 {@link #resolve(String)}，校验整批答案后固定其中一个地址用于直连或
 * CONNECT。这里既校验又返回同一批地址，不会发生第二次系统解析造成的 TOCTOU。代理自身不使用本解析器，以允许本机/内网代理。
 *
 * <p>URL 里的 IPv4/IPv6 字面量同样走这条路径：路由器在固定地址之前无条件解析与校验原始目标，不因字面量跳过准入。
 *
 * <p>TLS 不受影响：路由器保留原始 targetName，SNI 与证书主机名校验仍按原始主机名执行，本类只负责地址准入。
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

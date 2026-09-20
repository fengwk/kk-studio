package fun.fengwk.kkstudio.platform.plugin.resource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** 主机名解析端口：把 DNS 解析变成可替换依赖，使「解析到的每一个地址都必须被校验」这条规则可以被测试固定。 */
@FunctionalInterface
interface HostResolver {

  /** 解析主机的全部地址；无法解析时抛出异常。 */
  List<InetAddress> resolve(String host) throws UnknownHostException;

  /** 生产实现：使用 JDK 解析。 */
  static HostResolver system() {
    return host -> List.of(InetAddress.getAllByName(host));
  }
}

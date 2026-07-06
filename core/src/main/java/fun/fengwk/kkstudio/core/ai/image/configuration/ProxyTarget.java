package fun.fengwk.kkstudio.core.ai.image.configuration;

import java.net.InetSocketAddress;
import java.net.Proxy;

import java.net.InetSocketAddress;
import java.net.Proxy;

/** ProxyTarget 表示一个标准化后的代理目标。 */
record ProxyTarget(String scheme, String host, int port, String username, String password) {

  Proxy toJavaNetProxy() {
    Proxy.Type type =
        "socks".equalsIgnoreCase(scheme) || "socks5".equalsIgnoreCase(scheme)
            ? Proxy.Type.SOCKS
            : Proxy.Type.HTTP;
    return new Proxy(type, new InetSocketAddress(host, port));
  }
}

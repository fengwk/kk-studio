package fun.fengwk.kkstudio.core.ai.image.configuration;

import java.io.IOException;
import java.net.Authenticator;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 解析标准代理环境变量。
 */
public final class ProxySettings {

    private final ProxyTarget httpProxy;
    private final ProxyTarget httpsProxy;
    private final List<NoProxyRule> noProxyRules;

    private ProxySettings(ProxyTarget httpProxy, ProxyTarget httpsProxy, List<NoProxyRule> noProxyRules) {
        this.httpProxy = httpProxy;
        this.httpsProxy = httpsProxy;
        this.noProxyRules = noProxyRules;
    }

    public static ProxySettings fromEnvironment(Map<String, String> env) {
        return new ProxySettings(
                resolveProxy(env, "http_proxy", "HTTP_PROXY"),
                resolveProxy(env, "https_proxy", "HTTPS_PROXY"),
                resolveNoProxy(env, "no_proxy", "NO_PROXY")
        );
    }

    public boolean isEnabled() {
        return httpProxy != null || httpsProxy != null;
    }

    public ProxySelector toProxySelector() {
        return new ProxySelector() {
            @Override
            public List<java.net.Proxy> select(URI uri) {
                if (uri == null) {
                    throw new IllegalArgumentException("uri must not be null");
                }
                if (shouldBypass(uri)) {
                    return List.of(java.net.Proxy.NO_PROXY);
                }
                ProxyTarget proxy = proxyForScheme(uri.getScheme());
                if (proxy == null) {
                    return List.of(java.net.Proxy.NO_PROXY);
                }
                return List.of(proxy.toJavaNetProxy());
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // 由请求方处理失败。
            }
        };
    }

    public Authenticator toAuthenticator() {
        Map<String, PasswordAuthentication> credentialsByAuthority = new LinkedHashMap<>();
        registerCredential(credentialsByAuthority, httpProxy);
        registerCredential(credentialsByAuthority, httpsProxy);
        if (credentialsByAuthority.isEmpty()) {
            return null;
        }

        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                return credentialsByAuthority.get(authorityKey(getRequestingHost(), getRequestingPort()));
            }
        };
    }

    private ProxyTarget proxyForScheme(String scheme) {
        String normalizedScheme = isBlank(scheme) ? "" : scheme.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedScheme) {
            case "http", "ws" -> httpProxy;
            case "https", "wss" -> httpsProxy != null ? httpsProxy : httpProxy;
            default -> httpsProxy != null ? httpsProxy : httpProxy;
        };
    }

    private boolean shouldBypass(URI uri) {
        String host = uri.getHost();
        if (isBlank(host)) {
            return false;
        }
        String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
        for (NoProxyRule noProxyRule : noProxyRules) {
            if (noProxyRule.matches(normalizedHost)) {
                return true;
            }
        }
        return isImplicitLocalBypassHost(normalizedHost);
    }

    private boolean isImplicitLocalBypassHost(String host) {
        if (isBlank(host)) {
            return false;
        }
        if ("localhost".equals(host) || !host.contains(".")) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                return true;
            }
            if (address instanceof Inet4Address ipv4) {
                byte[] bytes = ipv4.getAddress();
                int first = bytes[0] & 0xFF;
                int second = bytes[1] & 0xFF;
                if (first == 100 && second >= 64 && second <= 127) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return false;
    }

    private static void registerCredential(Map<String, PasswordAuthentication> credentialsByAuthority, ProxyTarget proxy) {
        if (proxy == null || isBlank(proxy.username())) {
            return;
        }
        credentialsByAuthority.put(
                authorityKey(proxy.host(), proxy.port()),
                new PasswordAuthentication(proxy.username(), (proxy.password() == null ? "" : proxy.password()).toCharArray())
        );
    }

    private static String authorityKey(String host, int port) {
        return (host == null ? "" : host.toLowerCase(Locale.ROOT)) + ":" + port;
    }

    private static List<NoProxyRule> resolveNoProxy(Map<String, String> env, String lowerEnvName, String upperEnvName) {
        String rawValue = firstNonBlank(env.get(lowerEnvName), env.get(upperEnvName));
        if (isBlank(rawValue)) {
            return List.of();
        }

        List<NoProxyRule> rules = new ArrayList<>();
        Arrays.stream(rawValue.split(","))
                .map(String::trim)
                .filter(token -> !isBlank(token))
                .forEach(token -> parseNoProxyRule(token).ifPresent(rules::add));
        return List.copyOf(rules);
    }

    private static java.util.Optional<NoProxyRule> parseNoProxyRule(String token) {
        if ("*".equals(token)) {
            return java.util.Optional.of(host -> true);
        }
        if (token.startsWith(".")) {
            String normalizedSuffix = token.substring(1).trim().toLowerCase(Locale.ROOT);
            if (isValidHostToken(normalizedSuffix)) {
                return java.util.Optional.of(host -> host.equals(normalizedSuffix) || host.endsWith("." + normalizedSuffix));
            }
            return java.util.Optional.empty();
        }
        if (token.contains("/")) {
            return parseIpv4CidrRule(token);
        }
        if (isValidIpv4(token)) {
            return java.util.Optional.of(host -> host.equals(token));
        }
        String normalizedHost = token.trim().toLowerCase(Locale.ROOT);
        if (isValidHostToken(normalizedHost)) {
            return java.util.Optional.of(host -> host.equals(normalizedHost));
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<NoProxyRule> parseIpv4CidrRule(String token) {
        int separator = token.indexOf('/');
        if (separator <= 0 || separator == token.length() - 1) {
            return java.util.Optional.empty();
        }
        String address = token.substring(0, separator).trim();
        String prefixText = token.substring(separator + 1).trim();
        if (!isValidIpv4(address)) {
            return java.util.Optional.empty();
        }
        try {
            int prefixLength = Integer.parseInt(prefixText);
            if (prefixLength < 0 || prefixLength > 32) {
                return java.util.Optional.empty();
            }
            int subnetMask = prefixLength == 0 ? 0 : -1 << (32 - prefixLength);
            int expectedAddress = ipv4ToInt(address) & subnetMask;
            return java.util.Optional.of(host -> isValidIpv4(host) && (ipv4ToInt(host) & subnetMask) == expectedAddress);
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private static boolean isValidHostToken(String token) {
        if (isBlank(token) || token.contains("://") || token.contains("/") || token.contains(":")) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '.' || ch == '-' || ch == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidIpv4(String value) {
        try {
            InetAddress address = InetAddress.getByName(value);
            return address instanceof Inet4Address && value.equals(address.getHostAddress());
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static int ipv4ToInt(String value) {
        try {
            byte[] bytes = InetAddress.getByName(value).getAddress();
            return ((bytes[0] & 0xFF) << 24)
                    | ((bytes[1] & 0xFF) << 16)
                    | ((bytes[2] & 0xFF) << 8)
                    | (bytes[3] & 0xFF);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + value, e);
        }
    }

    private static ProxyTarget resolveProxy(Map<String, String> env, String lowerEnvName, String upperEnvName) {
        String rawValue = firstNonBlank(env.get(lowerEnvName), env.get(upperEnvName));
        if (isBlank(rawValue)) {
            return null;
        }

        String normalized = normalizeProxyUri(rawValue.trim());
        URI uri = URI.create(normalized);
        String scheme = isBlank(uri.getScheme()) ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (isBlank(host)) {
            throw new IllegalArgumentException("Proxy host is missing: " + rawValue);
        }
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort(scheme);
        String username = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (!isBlank(userInfo)) {
            int separator = userInfo.indexOf(':');
            if (separator >= 0) {
                username = userInfo.substring(0, separator);
                password = userInfo.substring(separator + 1);
            } else {
                username = userInfo;
            }
        }
        return new ProxyTarget(scheme, host, port, username, password);
    }

    private static String normalizeProxyUri(String rawValue) {
        return rawValue.contains("://") ? rawValue : "http://" + rawValue;
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "https" -> 443;
            case "socks", "socks5" -> 1080;
            default -> 80;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private interface NoProxyRule {
        boolean matches(String host);
    }

    private record ProxyTarget(String scheme, String host, int port, String username, String password) {

        private java.net.Proxy toJavaNetProxy() {
            java.net.Proxy.Type type = "socks".equalsIgnoreCase(scheme) || "socks5".equalsIgnoreCase(scheme)
                    ? java.net.Proxy.Type.SOCKS
                    : java.net.Proxy.Type.HTTP;
            return new java.net.Proxy(type, new InetSocketAddress(host, port));
        }

    }

}

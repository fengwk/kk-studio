package fun.fengwk.kkstudio.core.ai.image.configuration;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** NoProxyRules 负责解析并匹配标准 no_proxy 规则。 */
final class NoProxyRules {

  private final List<NoProxyRule> rules;

  private NoProxyRules(List<NoProxyRule> rules) {
    this.rules = rules;
  }

  static NoProxyRules fromEnvironment(
      Map<String, String> env, String lowerEnvName, String upperEnvName) {
    String rawValue =
        ProxyEnvironmentParser.firstNonBlank(env.get(lowerEnvName), env.get(upperEnvName));
    if (ProxyEnvironmentParser.isBlank(rawValue)) {
      return new NoProxyRules(List.of());
    }

    List<NoProxyRule> rules = new ArrayList<>();
    Arrays.stream(rawValue.split(","))
        .map(String::trim)
        .filter(token -> !ProxyEnvironmentParser.isBlank(token))
        .forEach(token -> parseRule(token).ifPresent(rules::add));
    return new NoProxyRules(List.copyOf(rules));
  }

  boolean matches(URI uri) {
    String host = uri.getHost();
    if (ProxyEnvironmentParser.isBlank(host)) {
      return false;
    }

    String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
    for (NoProxyRule rule : rules) {
      if (rule.matches(normalizedHost)) {
        return true;
      }
    }
    return isImplicitLocalBypassHost(normalizedHost);
  }

  private boolean isImplicitLocalBypassHost(String host) {
    if (ProxyEnvironmentParser.isBlank(host)) {
      return false;
    }
    if ("localhost".equals(host) || !host.contains(".")) {
      return true;
    }
    Integer ipv4 = parseIpv4(host);
    if (ipv4 != null) {
      return isImplicitLocalIpv4(ipv4);
    }
    InetAddress address = parseIpLiteral(host);
    if (address == null) {
      return false;
    }
    return address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress();
  }

  private static Optional<NoProxyRule> parseRule(String token) {
    if ("*".equals(token)) {
      return Optional.of(host -> true);
    }
    if (token.startsWith(".")) {
      String normalizedSuffix = token.substring(1).trim().toLowerCase(Locale.ROOT);
      if (isValidHostToken(normalizedSuffix)) {
        return Optional.of(
            host -> host.equals(normalizedSuffix) || host.endsWith("." + normalizedSuffix));
      }
      return Optional.empty();
    }
    if (token.contains("/")) {
      return parseIpv4CidrRule(token);
    }
    if (isValidIpv4(token)) {
      return Optional.of(host -> host.equals(token));
    }

    String normalizedHost = token.trim().toLowerCase(Locale.ROOT);
    if (isValidHostToken(normalizedHost)) {
      return Optional.of(host -> host.equals(normalizedHost));
    }
    return Optional.empty();
  }

  private static Optional<NoProxyRule> parseIpv4CidrRule(String token) {
    int separator = token.indexOf('/');
    if (separator <= 0 || separator == token.length() - 1) {
      return Optional.empty();
    }
    String address = token.substring(0, separator).trim();
    String prefixText = token.substring(separator + 1).trim();
    if (!isValidIpv4(address)) {
      return Optional.empty();
    }
    try {
      int prefixLength = Integer.parseInt(prefixText);
      if (prefixLength < 0 || prefixLength > 32) {
        return Optional.empty();
      }
      int subnetMask = prefixLength == 0 ? 0 : -1 << (32 - prefixLength);
      int expectedAddress = ipv4ToInt(address) & subnetMask;
      return Optional.of(
          host -> isValidIpv4(host) && (ipv4ToInt(host) & subnetMask) == expectedAddress);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static boolean isValidHostToken(String token) {
    if (ProxyEnvironmentParser.isBlank(token)
        || token.contains("://")
        || token.contains("/")
        || token.contains(":")) {
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
    return parseIpv4(value) != null;
  }

  private static Integer parseIpv4(String value) {
    if (ProxyEnvironmentParser.isBlank(value)) {
      return null;
    }
    String[] segments = value.split("\\.", -1);
    if (segments.length != 4) {
      return null;
    }
    int result = 0;
    for (String segment : segments) {
      if (segment.isEmpty()) {
        return null;
      }
      int octet = 0;
      for (int i = 0; i < segment.length(); i++) {
        char ch = segment.charAt(i);
        if (!Character.isDigit(ch)) {
          return null;
        }
        octet = octet * 10 + (ch - '0');
        if (octet > 255) {
          return null;
        }
      }
      result = (result << 8) | octet;
    }
    return result;
  }

  private static int ipv4ToInt(String value) {
    Integer parsed = parseIpv4(value);
    if (parsed == null) {
      throw new IllegalArgumentException("Invalid IPv4 address: " + value);
    }
    return parsed;
  }

  private static boolean isImplicitLocalIpv4(int ipv4) {
    int first = (ipv4 >>> 24) & 0xFF;
    int second = (ipv4 >>> 16) & 0xFF;
    return ipv4 == 0
        || first == 10
        || first == 127
        || (first == 169 && second == 254)
        || (first == 172 && second >= 16 && second <= 31)
        || (first == 192 && second == 168)
        || (first == 100 && second >= 64 && second <= 127);
  }

  private static InetAddress parseIpLiteral(String host) {
    String candidate = stripIpv6Brackets(host);
    if (candidate.indexOf(':') < 0) {
      return null;
    }
    try {
      return InetAddress.getByName(candidate);
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private static String stripIpv6Brackets(String host) {
    if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
      return host.substring(1, host.length() - 1);
    }
    return host;
  }

  private interface NoProxyRule {
    boolean matches(String host);
  }
}

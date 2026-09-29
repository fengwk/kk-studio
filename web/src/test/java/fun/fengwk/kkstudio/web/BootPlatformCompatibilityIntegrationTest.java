package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 验证真实运行的 Boot 4 平台：内嵌容器必须提供 Servlet 6.1 能力，且 classpath 上所有 Netty 构件必须共享 Boot BOM 管理的同一个 4.2.x 版本。
 *
 * <p>根 POM 曾把四个 {@code tomcat-embed-*} 固定为 10.1.59（Servlet 6.0），并在 Boot BOM 之前导入 netty-bom {@code
 * 4.1.137.Final}，等于把 Boot 4 的受支持平台拆回上一代容器与网络栈。本测试只从活容器与已解析构件自带的版本清单读取平台 major/minor，因此不会因 patch
 * 或小版本升级失效，但会捕捉任何把平台降级回 Tomcat 10 / Servlet 6.0 / Netty 4.1 的改动。
 */
class BootPlatformCompatibilityIntegrationTest extends WebPostgresTestSupport {

  /** Tomcat 的 {@code server.info} 形如 {@code Apache Tomcat/11.0.24}；只取 major，不写死 patch。 */
  private static final Pattern TOMCAT_MAJOR_VERSION = Pattern.compile("Tomcat/(\\d+)");

  /** 每个 Netty 构件都自带该清单，逐条记录自身 artifactId 与版本，用于核对构件间是否一致。 */
  private static final String NETTY_VERSION_MANIFEST = "META-INF/io.netty.versions.properties";

  private static final String NETTY_VERSION_SUFFIX = ".version";

  @LocalServerPort private int port;

  @Autowired private ApplicationContext applicationContext;

  /**
   * 测试意图：Boot 4.0.x 只支持 Servlet 6.1 容器（Tomcat 11.0.x）。真实 HTTP 往返证明端口上确实有一个按 Servlet 6.1 提供服务的
   * Tomcat 11，而不是仅类路径里存在 API；一旦根 POM 再次固定 Tomcat 10.1 就会在此失败。
   */
  @Test
  void embeddedContainerServesServlet61OnBootManagedTomcat11() throws Exception {
    assertTrue(port > 0, "the embedded container must listen on a real port");
    ServletContext servletContext =
        ((ServletWebServerApplicationContext) applicationContext).getServletContext();

    // 纯存活端点不依赖数据库、S3 或外部服务，因此这里的 200 只证明容器在该端口提供 servlet 服务。
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), "the embedded container must serve /healthz");

    assertEquals(
        6, servletContext.getMajorVersion(), "Boot 4 requires a Servlet 6.1 or newer container");
    assertTrue(
        servletContext.getMinorVersion() >= 1,
        "Boot 4 requires a Servlet 6.1 or newer container, actual minor="
            + servletContext.getMinorVersion());

    String serverInfo = servletContext.getServerInfo();
    Matcher tomcatMajor = TOMCAT_MAJOR_VERSION.matcher(serverInfo);
    assertTrue(tomcatMajor.find(), "the embedded container must be Tomcat, actual=" + serverInfo);
    assertEquals(
        11,
        Integer.parseInt(tomcatMajor.group(1)),
        "Boot 4 supports Tomcat 11.0.x, actual=" + serverInfo);
  }

  /**
   * 测试意图：Netty 只由 Boot BOM 拥有——AWS SDK 的 netty-nio-client、native epoll 等构件必须解析成同一个 4.2.x 版本。版本不一致会让
   * transport 与 native transport 混用，而 4.1.x 则是被移除的上一代平台。
   */
  @Test
  void resolvedNettyArtifactsShareBootManagedPlatformVersion() throws Exception {
    Map<String, String> artifactVersions = readResolvedNettyArtifactVersions();
    assertFalse(
        artifactVersions.isEmpty(),
        "the Boot BOM must resolve Netty for its direct users (AWS SDK netty-nio-client);"
            + " delete this assertion with the last Netty consumer");
    Set<String> distinctVersions = new TreeSet<>(artifactVersions.values());
    assertEquals(
        1,
        distinctVersions.size(),
        "every resolved Netty artifact must share the Boot-managed version, actual="
            + artifactVersions);
    String nettyVersion = distinctVersions.iterator().next();
    assertTrue(
        nettyVersion.startsWith("4.2."),
        "Boot 4.0.8 manages Netty 4.2.x, actual=" + nettyVersion + " for " + artifactVersions);
  }

  /** 汇总 classpath 上每个 Netty 构件自带清单里的 artifactId 与版本。 */
  private static Map<String, String> readResolvedNettyArtifactVersions() throws Exception {
    Map<String, String> artifactVersions = new TreeMap<>();
    Enumeration<URL> manifests =
        BootPlatformCompatibilityIntegrationTest.class
            .getClassLoader()
            .getResources(NETTY_VERSION_MANIFEST);
    while (manifests.hasMoreElements()) {
      Properties manifest = new Properties();
      try (InputStream in = manifests.nextElement().openStream()) {
        manifest.load(in);
      }
      for (String key : manifest.stringPropertyNames()) {
        if (key.endsWith(NETTY_VERSION_SUFFIX)) {
          artifactVersions.put(
              key.substring(0, key.length() - NETTY_VERSION_SUFFIX.length()),
              manifest.getProperty(key));
        }
      }
    }
    return artifactVersions;
  }
}

package fun.fengwk.kkstudio.platform.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * 意图：{@code kk-studio.cluster.internal-url} 一经配置必须是经证书校验的 https origin，非法配置在绑定时即失败；未配置时单节点部署行为不变。
 */
class ClusterPropertiesTest {

  private static final String SECRET = "test-cluster-secret-0123456789abcdef";

  /** 合法的 https origin 照常参与跨节点路由，并规范化为裸 origin（去掉尾部斜杠与空白）。 */
  @Test
  void acceptsHttpsOriginAndNormalizesIt() {
    ClusterProperties properties = new ClusterProperties();
    properties.setInternalUrl("  https://node-a.internal:8443/  ");
    properties.setSecret(SECRET);

    assertEquals("https://node-a.internal:8443", properties.getInternalUrl());
    assertEquals(
        Optional.of(URI.create("https://node-a.internal:8443")), properties.internalOrigin());
    assertTrue(properties.internalOriginConfigured());
    assertTrue(properties.remoteRoutingConfigured());
  }

  /** 明文 http 一旦配置就在绑定时失败，错误信息指明配置项与 https 要求。 */
  @Test
  void rejectsPlaintextInternalUrl() {
    ClusterProperties properties = new ClusterProperties();

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> properties.setInternalUrl("http://node-a.internal:8080"));

    assertTrue(error.getMessage().contains("kk-studio.cluster.internal-url"));
    assertTrue(error.getMessage().contains("https"));
  }

  /** 非 http(s) scheme 同样在绑定时失败。 */
  @Test
  void rejectsOtherSchemes() {
    ClusterProperties properties = new ClusterProperties();

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> properties.setInternalUrl("ftp://node-a.internal:8443"));

    assertTrue(error.getMessage().contains("kk-studio.cluster.internal-url"));
  }

  /** origin 形状规则不变：不含 path/userinfo/query/fragment。 */
  @Test
  void rejectsOriginWithPathUserInfoQueryOrFragment() {
    ClusterProperties properties = new ClusterProperties();

    for (String invalid :
        new String[] {
          "https://node-a.internal:8443/api",
          "https://user@node-a.internal:8443",
          "https://node-a.internal:8443?probe=1",
          "https://node-a.internal:8443#fragment"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> properties.setInternalUrl(invalid),
          "must reject " + invalid);
    }
  }

  /** 未配置 internal-url 时退回单节点部署：不解析 origin、不参与跨节点路由。 */
  @Test
  void absentInternalUrlKeepsSingleNodeDeployment() {
    ClusterProperties properties = new ClusterProperties();
    assertFalse(properties.internalOriginConfigured());
    assertFalse(properties.remoteRoutingConfigured());
    assertTrue(properties.internalOrigin().isEmpty());

    properties.setInternalUrl("   ");
    assertFalse(properties.internalOriginConfigured());
    assertTrue(properties.internalOrigin().isEmpty());

    properties.setSecret(SECRET);
    assertFalse(properties.remoteRoutingConfigured());
  }

  /** ssl-bundle 是可选的 Spring Boot SSL bundle 名：配置时按 canonical 属性名绑定，未配置时为空并使用 JVM 默认信任。 */
  @Test
  void bindsOptionalSslBundle() {
    assertEquals(
        "cluster-internal",
        bind(Map.of("kk-studio.cluster.ssl-bundle", "cluster-internal")).getSslBundle());
    assertNull(
        bind(Map.of("kk-studio.cluster.internal-url", "https://node-a.internal:8443"))
            .getSslBundle());
  }

  private static ClusterProperties bind(Map<String, Object> source) {
    ConfigurableEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", source));
    return Binder.get(environment)
        .bind("kk-studio.cluster", Bindable.of(ClusterProperties.class))
        .get();
  }
}

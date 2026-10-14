package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec.DeepLink;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec.None;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec.StaticKey;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 封闭的认证声明三态：region 候选校验由 DeepLink 承接，三态与交互式 authHandler 必须一致。 */
class PluginAuthSpecTest {

  private static final PluginAuthHandler HANDLER =
      new PluginAuthHandler() {
        @Override
        public String loginUrl(String region) {
          return "https://example.invalid/login";
        }

        @Override
        public PluginCredentialMaterial complete(String callbackUrl) {
          throw new UnsupportedOperationException();
        }
      };

  @Test
  void deepLinkFreezesRegionsAndMatchesCaseSensitively() {
    DeepLink deepLink = new DeepLink(List.of("CN", "en"));

    assertEquals(List.of("CN", "en"), deepLink.regions());
    assertTrue(deepLink.acceptsRegion("CN"));
    assertTrue(deepLink.acceptsRegion("en"));
    assertFalse(deepLink.acceptsRegion("cn"));
    assertFalse(deepLink.acceptsRegion(null));
    assertFalse(deepLink.acceptsRegion("other"));
  }

  @Test
  void deepLinkRejectsEmptyOrIllegalRegionCandidates() {
    assertThrows(NullPointerException.class, () -> new DeepLink(null));
    assertThrows(IllegalArgumentException.class, () -> new DeepLink(List.of()));
    assertThrows(IllegalArgumentException.class, () -> new DeepLink(List.of("CN CN")));
    assertThrows(IllegalArgumentException.class, () -> new DeepLink(List.of("CN", "CN")));
    assertThrows(IllegalArgumentException.class, () -> new DeepLink(List.of(" ")));
    assertThrows(IllegalArgumentException.class, () -> new DeepLink(List.of("CN ")));
    assertThrows(IllegalArgumentException.class, () -> new DeepLink(List.of("r".repeat(65))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DeepLink(List.of("r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9")));
  }

  @Test
  void staticKeyRequiresAFieldNameShape() {
    StaticKey staticKey = new StaticKey("apiKey", Set.of());
    assertEquals("apiKey", staticKey.secretField());
    assertTrue(staticKey.contextFields().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> new StaticKey(null, Set.of()));
    assertThrows(IllegalArgumentException.class, () -> new StaticKey("", Set.of()));
    assertThrows(IllegalArgumentException.class, () -> new StaticKey("1bad", Set.of()));
    assertThrows(IllegalArgumentException.class, () -> new StaticKey("has space", Set.of()));
  }

  /** 只有 StaticKey 声明 contextFields：None/DeepLink 的目标由固定协议控制，因此必须为空。 */
  @Test
  void contextFieldsAreDeclaredOnlyByStaticKey() {
    StaticKey staticKey = new StaticKey("apiKey", Set.of("region", "projectId"));
    assertEquals(Set.of("region", "projectId"), staticKey.contextFields());
    assertTrue(new None().contextFields().isEmpty());
    assertTrue(new DeepLink(List.of("CN")).contextFields().isEmpty());

    assertThrows(NullPointerException.class, () -> new StaticKey("apiKey", null));
    assertThrows(IllegalArgumentException.class, () -> new StaticKey("apiKey", Set.of("1bad")));
    assertThrows(
        IllegalArgumentException.class, () -> new StaticKey("apiKey", Set.of("has space")));
  }

  /** 只有 DeepLink 允许存在交互式 authHandler；None/StaticKey 声明了 handler 或 DeepLink 缺少 handler 都必须失败。 */
  @Test
  void authenticationSpecMustMatchAuthHandlerPresence() {
    new None().requireConsistentWith(Optional.empty());
    new StaticKey("apiKey", Set.of()).requireConsistentWith(Optional.empty());
    new DeepLink(List.of("CN")).requireConsistentWith(Optional.of(HANDLER));

    assertThrows(
        IllegalStateException.class, () -> new None().requireConsistentWith(Optional.of(HANDLER)));
    assertThrows(
        IllegalStateException.class,
        () -> new StaticKey("apiKey", Set.of()).requireConsistentWith(Optional.of(HANDLER)));
    assertThrows(
        IllegalStateException.class,
        () -> new DeepLink(List.of("CN")).requireConsistentWith(Optional.empty()));
    assertThrows(NullPointerException.class, () -> new None().requireConsistentWith(null));
  }
}

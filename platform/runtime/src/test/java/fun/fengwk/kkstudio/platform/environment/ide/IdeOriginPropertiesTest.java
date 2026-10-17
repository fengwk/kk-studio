package fun.fengwk.kkstudio.platform.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

/**
 * 意图：IDE 独立 Origin 配置必须严格 fail-closed —— 缺配置明确不可用，模板必须恰好含一个 {environmentId} 占位符且只能是 bare http(s)
 * origin；bootstrap 入口路径必须受控，避免任意 proxy 地址或 Studio 同源回退。
 */
class IdeOriginPropertiesTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  @Test
  void unconfiguredOriginFailsClosed() {
    IdeOriginProperties properties = new IdeOriginProperties();
    assertFalse(properties.configured());
    assertThrows(IllegalStateException.class, () -> properties.requireOrigin(ENV));
  }

  @Test
  void resolvesTemplatedOrigin() {
    IdeOriginProperties properties = new IdeOriginProperties();
    properties.setOriginTemplate("https://{environmentId}.ide.example.com");
    assertTrue(properties.configured());
    assertEquals("https://" + ENV + ".ide.example.com", properties.requireOrigin(ENV));
    assertEquals(
        "https://" + ENV + ".ide.example.com/__ide/bootstrap?token=abc",
        properties.bootstrapEntryUrl(ENV, "abc"));
  }

  @Test
  void rejectsMalformedTemplates() {
    for (String template :
        new String[] {
          "https://ide.example.com",
          "https://{environmentId}.{environmentId}.ide.example.com",
          "ftp://{environmentId}.ide.example.com",
          "https://user@{environmentId}.ide.example.com",
          "https://{environmentId}.ide.example.com/path",
          "https://{environmentId}.ide.example.com?q=1",
          "https://{environmentId}.ide.example.com#frag"
        }) {
      IdeOriginProperties properties = new IdeOriginProperties();
      properties.setOriginTemplate(template);
      assertThrows(
          IllegalStateException.class,
          () -> properties.requireOrigin(ENV),
          "template must be rejected: " + template);
    }
  }

  @Test
  void rejectsUnsafeBootstrapPath() {
    IdeOriginProperties properties = new IdeOriginProperties();
    properties.setOriginTemplate("https://{environmentId}.ide.example.com");
    for (String path : new String[] {"", "relative", "//evil", "/x?y=1", "/x#f"}) {
      properties.setBootstrapPath(path);
      assertThrows(
          IllegalStateException.class,
          () -> properties.bootstrapEntryUrl(ENV, "abc"),
          "bootstrap path must be rejected: " + path);
    }
  }
}

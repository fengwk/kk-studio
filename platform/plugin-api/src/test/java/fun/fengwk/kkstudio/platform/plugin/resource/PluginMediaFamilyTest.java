package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** PluginMediaFamily 让调用方声明媒体族而非伪造具体 MIME，测试锁定各族的命名空间判定。 */
class PluginMediaFamilyTest {

  @Test
  void eachFamilyAcceptsItsOwnNamespace() {
    assertTrue(PluginMediaFamily.IMAGE.accepts("image/png"));
    assertTrue(PluginMediaFamily.AUDIO.accepts("audio/mpeg"));
    assertTrue(PluginMediaFamily.VIDEO.accepts("video/mp4"));
    assertTrue(PluginMediaFamily.DOCUMENT.accepts("application/pdf"));
  }

  @Test
  void matchingIsCaseInsensitive() {
    assertTrue(PluginMediaFamily.IMAGE.accepts("IMAGE/PNG"));
  }

  @Test
  void mediaFamiliesDoNotAcceptOtherNamespaces() {
    assertFalse(PluginMediaFamily.IMAGE.accepts("audio/mpeg"));
    assertFalse(PluginMediaFamily.AUDIO.accepts("video/mp4"));
    assertFalse(PluginMediaFamily.VIDEO.accepts("image/png"));
    assertFalse(PluginMediaFamily.DOCUMENT.accepts("not-a-media-type"));
  }

  @Test
  void blankOrNullMediaTypeIsNeverAccepted() {
    for (PluginMediaFamily family : PluginMediaFamily.values()) {
      assertFalse(family.accepts(null));
      assertFalse(family.accepts(""));
      assertFalse(family.accepts("   "));
    }
  }
}

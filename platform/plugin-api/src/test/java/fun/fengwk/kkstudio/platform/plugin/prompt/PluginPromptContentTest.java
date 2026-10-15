package fun.fengwk.kkstudio.platform.plugin.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/**
 * Prompt 窄端口值对象与上下文契约。
 *
 * <p>测试意图：内容片段与暂存句柄只携带非 null 的 JDK 值；空白文本片段是合法内容（是否可接受由运行时 admission 判定），null 则在构造点确定性失败。
 */
class PluginPromptContentTest {

  @Test
  void keepsOrderedTextAndMediaFragmentsWithBlankTextAllowed() {
    PluginPromptResources.StagedResource staged =
        new PluginPromptResources.StagedResource(UUID.randomUUID(), UUID.randomUUID());
    List<PluginPromptContent> contents =
        List.of(
            new PluginPromptContent.Text("manifest table"),
            new PluginPromptContent.Media(staged),
            new PluginPromptContent.Text("   \n"));

    assertEquals("manifest table", ((PluginPromptContent.Text) contents.get(0)).text());
    assertEquals(staged, ((PluginPromptContent.Media) contents.get(1)).resource());
    assertEquals("   \n", ((PluginPromptContent.Text) contents.get(2)).text());
    assertInstanceOf(PluginPromptContent.Text.class, contents.get(2));
  }

  @Test
  void rejectsNullFragments() {
    assertThrows(NullPointerException.class, () -> new PluginPromptContent.Text(null));
    assertThrows(NullPointerException.class, () -> new PluginPromptContent.Media(null));
  }

  @Test
  void rejectsStagedResourceWithMissingIdentity() {
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();

    assertThrows(
        NullPointerException.class, () -> new PluginPromptResources.StagedResource(uploadId, null));
    assertThrows(
        NullPointerException.class, () -> new PluginPromptResources.StagedResource(null, blobId));
    assertEquals(uploadId, new PluginPromptResources.StagedResource(uploadId, blobId).uploadId());
  }
}

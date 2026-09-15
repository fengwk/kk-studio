package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;

import java.util.EnumSet;
import java.util.Set;

/**
 * 测试意图：验证 {@link ProviderMediaCapabilities} 的不可变集合语义、{@code NONE} 默认值与 {@code supports}
 * 查询真值表，确保能力声明不会被外部可变集合篡改。
 */
class ProviderMediaCapabilitiesTest {

  @Test
  void noneDeclaresNoMediaAtAll() {
    assertTrue(ProviderMediaCapabilities.NONE.userModalities().isEmpty());
    assertTrue(ProviderMediaCapabilities.NONE.toolResultModalities().isEmpty());
    for (ModelInputModality modality : ModelInputModality.values()) {
      assertFalse(ProviderMediaCapabilities.NONE.supports(modality, false));
      assertFalse(ProviderMediaCapabilities.NONE.supports(modality, true));
    }
  }

  @Test
  void supportsDistinguishesUserFromToolResult() {
    ProviderMediaCapabilities capabilities =
        new ProviderMediaCapabilities(
            Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
            Set.of(ModelInputModality.IMAGE));

    assertTrue(capabilities.supports(ModelInputModality.IMAGE, false));
    assertTrue(capabilities.supports(ModelInputModality.DOCUMENT, false));
    assertFalse(capabilities.supports(ModelInputModality.AUDIO, false));
    assertFalse(capabilities.supports(ModelInputModality.VIDEO, false));
    // 工具结果能力独立于用户能力：DOCUMENT 只在用户侧
    assertTrue(capabilities.supports(ModelInputModality.IMAGE, true));
    assertFalse(capabilities.supports(ModelInputModality.DOCUMENT, true));
  }

  @Test
  void copiesMutableInputAndRejectsExternalMutation() {
    Set<ModelInputModality> mutableUser = EnumSet.of(ModelInputModality.IMAGE);
    Set<ModelInputModality> mutableTool = EnumSet.of(ModelInputModality.AUDIO);
    ProviderMediaCapabilities capabilities =
        new ProviderMediaCapabilities(mutableUser, mutableTool);

    // 构造后修改入参不得影响已建能力
    mutableUser.add(ModelInputModality.VIDEO);
    assertEquals(Set.of(ModelInputModality.IMAGE), capabilities.userModalities());
    assertEquals(Set.of(ModelInputModality.AUDIO), capabilities.toolResultModalities());

    // 返回的集合本身必须不可变
    assertThrows(
        UnsupportedOperationException.class,
        () -> capabilities.userModalities().add(ModelInputModality.VIDEO));
    assertThrows(
        UnsupportedOperationException.class,
        () -> capabilities.toolResultModalities().add(ModelInputModality.VIDEO));
  }

  @Test
  void equalsAndHashCodeFollowSetContent() {
    ProviderMediaCapabilities first =
        new ProviderMediaCapabilities(
            Set.of(ModelInputModality.IMAGE), Set.of(ModelInputModality.DOCUMENT));
    ProviderMediaCapabilities second =
        new ProviderMediaCapabilities(
            Set.of(ModelInputModality.IMAGE), Set.of(ModelInputModality.DOCUMENT));
    ProviderMediaCapabilities differentTool =
        new ProviderMediaCapabilities(Set.of(ModelInputModality.IMAGE), Set.of());

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertFalse(first.equals(differentTool));
  }
}

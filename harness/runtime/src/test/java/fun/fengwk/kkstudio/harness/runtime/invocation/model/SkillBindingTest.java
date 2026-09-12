package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.UUID;

/** SkillBinding 的六字段冻结契约：来源环境、来源身份、描述与内容 revision 全部必填。 */
class SkillBindingTest {

  private static final EnvironmentId ENV_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID SOURCE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String REVISION =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void acceptsCompleteFrozenFacts() {
    SkillBinding binding =
        new SkillBinding(
            ENV_ID,
            SOURCE_ID,
            "web_search",
            "Search the web",
            "/home/dev/skills/web_search",
            REVISION);

    assertEquals(ENV_ID, binding.sourceEnvironmentId());
    assertEquals(SOURCE_ID, binding.sourceId());
    assertEquals("web_search", binding.name());
    assertEquals("Search the web", binding.description());
    assertEquals("/home/dev/skills/web_search", binding.baseDirectory());
    assertEquals(REVISION, binding.contentRevision());

    SkillBinding longDescription =
        new SkillBinding(ENV_ID, SOURCE_ID, "web_search", "d".repeat(1024), "/s", REVISION);
    assertEquals(1024, longDescription.description().length());
  }

  /** 缺少来源环境或来源身份的旧形状必须被拒绝：没有身份就无法精确定位冻结版本。 */
  @Test
  void rejectsMissingSourceIdentity() {
    assertThrows(
        NullPointerException.class,
        () -> new SkillBinding(null, SOURCE_ID, "name", "desc", "/s", REVISION));
    assertThrows(
        NullPointerException.class,
        () -> new SkillBinding(ENV_ID, null, "name", "desc", "/s", REVISION));
  }

  @Test
  void rejectsInvalidNameDescriptionAndDirectory() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, null, "desc", "/s", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, " ", "desc", "/s", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, " name", "desc", "/s", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name ", "desc", "/s", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "n".repeat(129), "desc", "/s", REVISION));

    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", null, "/s", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "", "/s", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", " desc", "/s", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "d".repeat(1025), "/s", REVISION));

    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "desc", null, REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "desc", " ", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "desc", "/s ".strip() + " ", REVISION));
  }

  /** contentRevision 是必须显式冻结的事实：缺失或空白不得被解释成“当前版本”。 */
  @Test
  void rejectsMissingContentRevision() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "desc", "/s", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "desc", "/s", " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "desc", "/s", " revision"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "desc", "/s", "a".repeat(40)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "desc", "/s", REVISION.toUpperCase()));
  }

  /** Runtime 冻结事实必须与 READY descriptor 使用同一 canonical 元数据与绝对目录契约。 */
  @Test
  void rejectsValuesOutsideTheDescriptorContract() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "bad\nname", "desc", "/s", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "bad\u0000desc", "/s", REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillBinding(ENV_ID, SOURCE_ID, "name", "desc", "relative", REVISION));
  }
}

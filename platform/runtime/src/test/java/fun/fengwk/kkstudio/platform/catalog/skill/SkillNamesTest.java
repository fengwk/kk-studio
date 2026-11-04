package fun.fengwk.kkstudio.platform.catalog.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

class SkillNamesTest {

  @Test
  void validatesSkillAndPackageNamesAtTheirBoundaries() {
    // 两个入口必须采用同一规则，不能通过目录名绕过包名校验。
    for (String valid : List.of("a", "0", "dev-2", "a".repeat(64))) {
      assertEquals(valid, SkillNames.canonicalSkillName(valid));
      assertEquals(valid, SkillNames.canonicalPackageName(valid));
    }
    for (String invalid :
        List.of("", "-a", "a-", "a--b", "A", "a_b", "a.b", "a/b", "中", "a".repeat(65))) {
      assertThrows(IllegalArgumentException.class, () -> SkillNames.canonicalSkillName(invalid));
      assertThrows(IllegalArgumentException.class, () -> SkillNames.canonicalPackageName(invalid));
    }
    assertThrows(IllegalArgumentException.class, () -> SkillNames.canonicalSkillName(null));
  }

  @Test
  void trimsDescriptionsAndCountsUnicodeCharacters() {
    // frontmatter 的换行和首尾空白归一后写入 manifest；长度按字符而非 UTF-16 单元计算。
    assertEquals("first\nsecond", SkillNames.canonicalDescription(" \nfirst\nsecond \n"));
    assertEquals("😀".repeat(1024), SkillNames.canonicalDescription("😀".repeat(1024)));
    for (String invalid : List.of("", " \n ", "a".repeat(1025), "a\u0000b", "a\rb")) {
      assertThrows(IllegalArgumentException.class, () -> SkillNames.canonicalDescription(invalid));
    }
    assertThrows(IllegalArgumentException.class, () -> SkillNames.canonicalDescription(null));
  }
}

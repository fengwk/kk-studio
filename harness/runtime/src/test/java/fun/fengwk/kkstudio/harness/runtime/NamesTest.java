package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/** Names 名称规范化 / 派生工具的白盒单元测试。 */
class NamesTest {

  @Test
  void normalizeCollapsesUnicodeWhitespaceToSingleSpaceAndTrims() {
    // 任意 Unicode 空白（含 \t \n 换行）折叠为单个普通空格；首尾（含全角空白 / NBSP 等 Unicode 空白）也一并去除。
    assertEquals("a b c", Names.normalize("  a\t b\n c  "));
    assertEquals("a b", Names.normalize("a\u3000\u3000b"));
    assertEquals("a b", Names.normalize(" \u3000a\u00a0\u00a0b\u2003 "));
    assertEquals("a", Names.normalize("\u00a0\u3000a\u2007"));
  }

  @Test
  void normalizeAcceptsUpToMaxCodePointsButRejectsOverlongNames() {
    // 手工名称上限 256 个 Unicode 码点：恰好 256 接受；超过 256 直接抛 IllegalArgumentException（绝不截断）。
    String exactly256 = "x".repeat(256);
    assertEquals(exactly256, Names.normalize(exactly256));
    assertThrows(IllegalArgumentException.class, () -> Names.normalize("x".repeat(257)));

    // emoji 按码点计数：256 个 emoji（256 码点 / 512 码元）接受，257 个拒绝。
    String emoji256 = "\uD83D\uDE00".repeat(256);
    assertEquals(emoji256, Names.normalize(emoji256));
    assertThrows(IllegalArgumentException.class, () -> Names.normalize("\uD83D\uDE00".repeat(257)));
  }

  @Test
  void normalizeRejectsNullAndBlank() {
    assertThrows(NullPointerException.class, () -> Names.normalize(null));
    assertThrows(IllegalArgumentException.class, () -> Names.normalize(""));
    assertThrows(IllegalArgumentException.class, () -> Names.normalize("   \t\n  "));
    // 纯 Unicode 空白同样视为 blank。
    assertThrows(IllegalArgumentException.class, () -> Names.normalize("\u3000\u00a0"));
  }

  @Test
  void defaultSessionNameUsesFirstEightCharsOfCanonicalUuid() {
    // session- 回退名 = "session-" + canonical UUID 前 8 位。
    UUID id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    assertEquals("session-00112233", Names.defaultSessionName(id));
  }

  @Test
  void defaultThreadNameUsesFirstEightCharsOfCanonicalUuid() {
    // branch- 回退名 = "branch-" + canonical UUID 前 8 位。
    UUID id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    assertEquals("branch-00112233", Names.defaultThreadName(id));
    assertEquals("main", Names.rootThreadName());
  }

  @Test
  void sessionNameFromUserTextCollapsesNormalizesAndTruncatesAtForty() {
    // Session 文本名（自动命名路径，不设 256 上限）：null / 空白文本返回 null（调用方回退）；否则折叠单行 + 前 40 码点，无省略号。
    assertNull(Names.sessionNameFromUserText(null));
    assertNull(Names.sessionNameFromUserText("   \t "));
    assertNull(Names.sessionNameFromUserText("\u3000\u00a0"));
    assertEquals("hi there", Names.sessionNameFromUserText("  hi \n there  "));
    assertEquals("a b", Names.sessionNameFromUserText(" a\u3000\u00a0 b "));
    String over = "a".repeat(60);
    String truncated = Names.sessionNameFromUserText(over);
    assertEquals(40, truncated.codePointCount(0, truncated.length()));
    assertEquals("a".repeat(40), truncated);
    // 60 个字符既可在自动命名路径截到 40，也可完整作为手工名称（未超 256 上限）。
    assertEquals(over, Names.normalize(over));
  }
}

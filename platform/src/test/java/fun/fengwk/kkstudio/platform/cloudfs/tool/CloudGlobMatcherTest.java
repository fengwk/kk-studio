package fun.fengwk.kkstudio.platform.cloudfs.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 验证 {@link CloudGlobMatcher} 的 segment-aware glob 匹配契约：
 *
 * <ul>
 *   <li>无 {@code /} 时按 basename 匹配；含 {@code /} 时按相对路径匹配；
 *   <li>{@code *} 不跨越 {@code /}，{@code **} 跨越目录，{@code ?} 单字符匹配；
 *   <li>正则特殊符号作为字面量正确转义。
 * </ul>
 */
class CloudGlobMatcherTest {

  @Test
  void basenameMatchingWithoutSlash() {
    // 意图：无 slash 的模式应匹配节点的 basename
    CloudGlobMatcher matcher = CloudGlobMatcher.compile("*.java");
    assertFalse(matcher.isPathPattern());
    assertTrue(matcher.matches("src/App.java", "App.java"));
    assertTrue(matcher.matches("deep/sub/dir/Test.java", "Test.java"));
    assertFalse(matcher.matches("src/App.kt", "App.kt"));
  }

  @Test
  void relativePathMatchingWithSlash() {
    // 意图：包含 slash 的模式匹配相对路径
    CloudGlobMatcher matcher = CloudGlobMatcher.compile("src/**/*.java");
    assertTrue(matcher.isPathPattern());
    assertTrue(matcher.matches("src/main/App.java", "App.java"));
    assertTrue(matcher.matches("src/a/b/c/Test.java", "Test.java"));
    assertFalse(matcher.matches("test/main/App.java", "App.java"));
  }

  @Test
  void doubleStarAtStartMatchesAnyDepth() {
    // 意图：以 ** 开头的模式支持匹配顶层或任意深度子目录
    CloudGlobMatcher matcher = CloudGlobMatcher.compile("**/*.md");
    assertTrue(matcher.matches("README.md", "README.md"));
    assertTrue(matcher.matches("docs/guide.md", "guide.md"));
    assertTrue(matcher.matches("a/b/c/doc.md", "doc.md"));
    assertFalse(matcher.matches("README.txt", "README.txt"));
  }

  @Test
  void singleStarDoesNotCrossDirectorySlash() {
    // 意图：单星号不跨越目录层级
    CloudGlobMatcher matcher = CloudGlobMatcher.compile("docs/*.md");
    assertTrue(matcher.matches("docs/intro.md", "intro.md"));
    assertFalse(matcher.matches("docs/sub/intro.md", "intro.md"));
  }

  @Test
  void questionMarkMatchesSingleCharacter() {
    // 意图：问号严格匹配单个非斜杠字符
    CloudGlobMatcher matcher = CloudGlobMatcher.compile("file?.txt");
    assertTrue(matcher.matches("file1.txt", "file1.txt"));
    assertTrue(matcher.matches("fileA.txt", "fileA.txt"));
    assertFalse(matcher.matches("file12.txt", "file12.txt"));
    assertFalse(matcher.matches("file.txt", "file.txt"));
  }

  @Test
  void literalSpecialCharactersAreEscaped() {
    // 意图：模式中的正则元字符（如 . + $ () 等）应当作字面量
    CloudGlobMatcher matcher = CloudGlobMatcher.compile("data(1)+[a].json");
    assertTrue(matcher.matches("data(1)+[a].json", "data(1)+[a].json"));
    assertFalse(matcher.matches("data1a.json", "data1a.json"));
  }

  @Test
  void emptyOrNullPatternThrowsException() {
    // 意图：空模式或 null 应拒绝
    assertThrows(NullPointerException.class, () -> CloudGlobMatcher.compile(null));
    assertThrows(IllegalArgumentException.class, () -> CloudGlobMatcher.compile(""));
  }

  @Test
  void matchesNullTargetReturnsFalse() {
    // 意图：目标为 null 时安全返回 false
    CloudGlobMatcher matcher = CloudGlobMatcher.compile("*.txt");
    assertFalse(matcher.matches("test.txt", null));

    CloudGlobMatcher pathMatcher = CloudGlobMatcher.compile("src/*.txt");
    assertFalse(pathMatcher.matches(null, "test.txt"));
  }

  @Test
  void additionalGlobPatternsAndEscaping() {
    // 意图：测试末尾/**、嵌入式**、转义符反斜杠及rawPattern访问
    CloudGlobMatcher endDoubleStar = CloudGlobMatcher.compile("docs/**");
    assertEquals("docs/**", endDoubleStar.rawPattern());
    assertTrue(endDoubleStar.matches("docs/sub/guide.md", "guide.md"));

    CloudGlobMatcher embedded = CloudGlobMatcher.compile("prefix**suffix");
    assertTrue(embedded.matches("prefix_middle_suffix", "prefix_middle_suffix"));

    CloudGlobMatcher escapedStar = CloudGlobMatcher.compile("star\\*file.txt");
    assertTrue(escapedStar.matches("star*file.txt", "star*file.txt"));
    assertFalse(escapedStar.matches("star_anything_file.txt", "star_anything_file.txt"));

    CloudGlobMatcher trailingBackslash = CloudGlobMatcher.compile("trailing\\");
    assertTrue(trailingBackslash.matches("trailing\\", "trailing\\"));
  }

  @Test
  void invalidGlobPatternThrowsGenericMessageWithoutLeakingFragmentOrCause() {
    // 意图：畸形 glob（如 RE2 不支持的非法转义符）报错固定通用消息，绝不泄漏 sensitive token 且无 cause
    String sensitive = "prefix\\kSECRET_TOKEN";
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> CloudGlobMatcher.compile(sensitive));
    assertEquals("Invalid glob pattern", error.getMessage());
    assertNull(error.getCause(), "cause must be null");
    assertFalse(error.getMessage().contains("SECRET_TOKEN"));
  }
}

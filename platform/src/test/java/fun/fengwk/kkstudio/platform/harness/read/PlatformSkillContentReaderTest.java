package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** {@link PlatformSkillContentReader} 的单元测试。 */
class PlatformSkillContentReaderTest {

  private SkillCatalogQueryService queryService;
  private SkillGitCache gitCache;
  private PlatformSkillContentReader reader;

  @BeforeEach
  void setUp() {
    queryService = mock(SkillCatalogQueryService.class);
    gitCache = mock(SkillGitCache.class);
    reader = new PlatformSkillContentReader(queryService, gitCache);
  }

  /** 找不到 Skill Package 时抛出确定性异常 */
  @Test
  void packageNotFoundThrowsPlatformReadException() {
    when(queryService.getPackage("unknown-pkg")).thenReturn(null);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () -> reader.readSkillFile("unknown-pkg", "dev", "SKILL.md"));
    assertTrue(error.getMessage().contains("skill package not found: unknown-pkg"));
  }

  /** Package 内不存在目标 Skill 时抛出确定性异常 */
  @Test
  void skillNotFoundInPackageThrowsPlatformReadException() {
    SkillPackage pkg = new SkillPackage();
    pkg.setPackageName("my-pkg");
    pkg.setCurrentCommit("c1");
    pkg.setSkills(List.of(new SkillManifestEntry("other-skill", "desc")));
    when(queryService.getPackage("my-pkg")).thenReturn(pkg);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "SKILL.md"));
    assertTrue(error.getMessage().contains("skill not found: my-pkg/dev"));
  }

  /** 相对路径为空白字符串时抛出确定性异常 */
  @Test
  void blankRelativePathThrowsPlatformReadException() {
    assertThrows(PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "   "));
  }

  /** 相对路径以斜杠开头时抛出确定性异常 */
  @Test
  void relativePathStartingWithSlashThrowsPlatformReadException() {
    assertThrows(
        PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "/SKILL.md"));
  }

  /** 相对路径包含反斜杠时抛出确定性异常 */
  @Test
  void relativePathWithBackslashThrowsPlatformReadException() {
    assertThrows(
        PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "sub\\SKILL.md"));
  }

  /** 相对路径包含控制字符时抛出确定性异常 */
  @Test
  void relativePathWithControlCharactersThrowsPlatformReadException() {
    assertThrows(
        PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "sub\nSKILL.md"));
  }

  /** 相对路径包含点或双点越界段时抛出确定性异常 */
  @Test
  void relativePathWithDotOrDotDotSegmentThrowsPlatformReadException() {
    assertThrows(
        PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "../SKILL.md"));
    assertThrows(
        PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "./SKILL.md"));
    assertThrows(
        PlatformReadException.class,
        () -> reader.readSkillFile("my-pkg", "dev", "sub/../SKILL.md"));
  }

  /** Package 尚未发布 Commit 时抛出确定性异常 */
  @Test
  void packageWithoutPublishedCommitThrowsPlatformReadException() {
    SkillPackage pkg = new SkillPackage();
    pkg.setPackageName("my-pkg");
    pkg.setCurrentCommit(null);
    pkg.setSkills(List.of(new SkillManifestEntry("dev", "desc")));
    when(queryService.getPackage("my-pkg")).thenReturn(pkg);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "SKILL.md"));
    assertTrue(error.getMessage().contains("skill package has no published commit"));
  }

  /** Git ensureCommit 失败时映射为确定性异常 */
  @Test
  void ensureCommitFailureThrowsPlatformReadException() {
    SkillPackage pkg = new SkillPackage();
    pkg.setPackageName("my-pkg");
    pkg.setRepositoryUrl("https://example.com/repo.git");
    pkg.setCurrentCommit("c1");
    pkg.setSkills(List.of(new SkillManifestEntry("dev", "desc")));
    when(queryService.getPackage("my-pkg")).thenReturn(pkg);

    doThrow(new SkillGitException("fetch failed")).when(gitCache).ensureCommit(any(), any(), any());

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "SKILL.md"));
    assertTrue(error.getMessage().contains("skill content is unavailable: fetch failed"));
  }

  /** Git readFile 失败时映射为确定性异常 */
  @Test
  void readFileFailureThrowsPlatformReadException() {
    SkillPackage pkg = new SkillPackage();
    pkg.setPackageName("my-pkg");
    pkg.setRepositoryUrl("https://example.com/repo.git");
    pkg.setCurrentCommit("c1");
    pkg.setSkills(List.of(new SkillManifestEntry("dev", "desc")));
    when(queryService.getPackage("my-pkg")).thenReturn(pkg);

    when(gitCache.readFile(eq("my-pkg"), eq("c1"), any()))
        .thenThrow(new SkillGitException("entry not found"));

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "SKILL.md"));
    assertTrue(error.getMessage().contains("failed to read skill file: entry not found"));
  }

  /** 成功读取已发布 Skill 文件内容并返回字节 */
  @Test
  void readSkillFileSuccessfullyReturnsBytes() {
    SkillPackage pkg = new SkillPackage();
    pkg.setPackageName("my-pkg");
    pkg.setRepositoryUrl("https://example.com/repo.git");
    pkg.setCurrentCommit("c1");
    pkg.setSkills(List.of(new SkillManifestEntry("dev", "desc")));
    when(queryService.getPackage("my-pkg")).thenReturn(pkg);

    byte[] expectedBytes = "# Dev Skill".getBytes(StandardCharsets.UTF_8);
    when(gitCache.readFile("my-pkg", "c1", "dev/SKILL.md")).thenReturn(expectedBytes);

    byte[] actualBytes = reader.readSkillFile("my-pkg", "dev", "SKILL.md");

    assertArrayEquals(expectedBytes, actualBytes);
    verify(gitCache).ensureCommit("my-pkg", "https://example.com/repo.git", "c1");
    verify(gitCache).readFile("my-pkg", "c1", "dev/SKILL.md");
  }
}

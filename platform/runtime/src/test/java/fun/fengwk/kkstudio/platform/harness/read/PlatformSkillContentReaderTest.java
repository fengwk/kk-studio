package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.InputEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.Type;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveFileReader;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveIndex;
import fun.fengwk.kkstudio.platform.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link PlatformSkillContentReader} 的单元测试。
 *
 * <p>覆盖：范围读取返回精确字节、缺失条目拒绝、缺少归档索引的 blob 拒绝、相对路径安全校验，以及异常原因链保留。
 */
class PlatformSkillContentReaderTest {

  private SkillPackageRepository skillPackageRepository;
  private StorageBlobManager blobManager;
  private InMemoryS3StorageService s3Storage;
  private SkillArchiveFileReader archiveFileReader;
  private PlatformSkillContentReader reader;

  @BeforeEach
  void setUp() {
    skillPackageRepository = mock(SkillPackageRepository.class);
    blobManager = mock(StorageBlobManager.class);
    s3Storage = new InMemoryS3StorageService();
    archiveFileReader = new SkillArchiveFileReader(blobManager, s3Storage);
    reader = new PlatformSkillContentReader(skillPackageRepository, archiveFileReader);
  }

  /** 测试意图：范围读取归档中的文件返回精确字节。 */
  @Test
  void readSkillFileSuccessfullyReturnsExactBytes() throws IOException {
    byte[] skillMd =
        "---\nname: dev\ndescription: dev skill\n---\n".getBytes(StandardCharsets.UTF_8);
    byte[] nestedFile = "nested content".getBytes(StandardCharsets.UTF_8);

    SkillArchiveBuilder builder = new SkillArchiveBuilder();
    SkillArchiveBuilder.Result archive =
        builder.fromEntries(
            List.of(
                new InputEntry("dev/SKILL.md", Type.FILE, false, skillMd),
                new InputEntry("dev/subdir/nested.txt", Type.FILE, false, nestedFile)));

    UUID blobId = UUID.randomUUID();
    s3Storage.putDirect(StorageObjectKeys.blobOriginal(blobId), archive.zip(), "application/zip");

    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setArchiveIndex(SkillArchiveIndex.from(archive.index()).encode());
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    SkillPackage pkg =
        new SkillPackage(
            "my-pkg",
            "desc",
            "v1",
            blobId,
            List.of(new SkillManifestEntry("dev", "dev skill")),
            Instant.now(),
            0L,
            Instant.now(),
            Instant.now(),
            null);
    when(skillPackageRepository.getPackage("my-pkg")).thenReturn(pkg);

    byte[] actual = reader.readSkillFile("my-pkg", "dev", "SKILL.md");
    assertArrayEquals(skillMd, actual);

    byte[] actualNested = reader.readSkillFile("my-pkg", "dev", "subdir/nested.txt");
    assertArrayEquals(nestedFile, actualNested);
  }

  /** 测试意图：找不到 Skill Package 时抛出确定性异常。 */
  @Test
  void packageNotFoundThrowsPlatformReadException() {
    when(skillPackageRepository.getPackage("unknown-pkg")).thenReturn(null);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () -> reader.readSkillFile("unknown-pkg", "dev", "SKILL.md"));
    assertTrue(error.getMessage().contains("skill package not found: unknown-pkg"));
  }

  /** 测试意图：Package 内不存在目标 Skill 时抛出确定性异常。 */
  @Test
  void skillNotFoundInPackageThrowsPlatformReadException() {
    SkillPackage pkg =
        new SkillPackage(
            "my-pkg",
            "desc",
            "v1",
            UUID.randomUUID(),
            List.of(new SkillManifestEntry("other-skill", "desc")),
            Instant.now(),
            0L,
            Instant.now(),
            Instant.now(),
            null);
    when(skillPackageRepository.getPackage("my-pkg")).thenReturn(pkg);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "SKILL.md"));
    assertTrue(error.getMessage().contains("skill not found: my-pkg/dev"));
  }

  /** 测试意图：Blob 缺少归档索引时拒绝读取。 */
  @Test
  void blobWithoutArchiveIndexThrowsPlatformReadException() {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setArchiveIndex(null);
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    SkillPackage pkg =
        new SkillPackage(
            "my-pkg",
            "desc",
            "v1",
            blobId,
            List.of(new SkillManifestEntry("dev", "desc")),
            Instant.now(),
            0L,
            Instant.now(),
            Instant.now(),
            null);
    when(skillPackageRepository.getPackage("my-pkg")).thenReturn(pkg);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", "SKILL.md"));
    assertTrue(error.getMessage().contains("failed to read skill file: blob has no archive index"));
  }

  /** 测试意图：归档索引中不存在目标条目时抛出确定性异常。 */
  @Test
  void missingEntryInArchiveThrowsPlatformReadException() {
    UUID blobId = UUID.randomUUID();
    SkillArchiveIndex index =
        new SkillArchiveIndex(
            List.of(new SkillArchiveIndex.Entry("dev/SKILL.md", 10L, 0L, 5L, "12345678")));
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setArchiveIndex(index.encode());
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    SkillPackage pkg =
        new SkillPackage(
            "my-pkg",
            "desc",
            "v1",
            blobId,
            List.of(new SkillManifestEntry("dev", "desc")),
            Instant.now(),
            0L,
            Instant.now(),
            Instant.now(),
            null);
    when(skillPackageRepository.getPackage("my-pkg")).thenReturn(pkg);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () -> reader.readSkillFile("my-pkg", "dev", "nonexistent.txt"));
    assertTrue(
        error
            .getMessage()
            .contains("failed to read skill file: archive entry not found: dev/nonexistent.txt"));
  }

  /** 测试意图：同名子目录是合法的相对路径，不得吞掉一级后误读。 */
  @Test
  void sameNamedSubdirectoryReadsExactFile() throws IOException {
    byte[] root = "---\nname: dev\ndescription: root\n---\nroot".getBytes(StandardCharsets.UTF_8);
    byte[] nested =
        "---\nname: dev\ndescription: nested\n---\nnested".getBytes(StandardCharsets.UTF_8);

    SkillArchiveBuilder builder = new SkillArchiveBuilder();
    SkillArchiveBuilder.Result archive =
        builder.fromEntries(
            List.of(
                new InputEntry("dev/SKILL.md", Type.FILE, false, root),
                new InputEntry("dev/dev/SKILL.md", Type.FILE, false, nested)));

    UUID blobId = UUID.randomUUID();
    s3Storage.putDirect(StorageObjectKeys.blobOriginal(blobId), archive.zip(), "application/zip");

    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setArchiveIndex(SkillArchiveIndex.from(archive.index()).encode());
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    SkillPackage pkg =
        new SkillPackage(
            "my-pkg",
            "desc",
            "v1",
            blobId,
            List.of(new SkillManifestEntry("dev", "desc")),
            Instant.now(),
            0L,
            Instant.now(),
            Instant.now(),
            null);
    when(skillPackageRepository.getPackage("my-pkg")).thenReturn(pkg);

    assertArrayEquals(nested, reader.readSkillFile("my-pkg", "dev", "dev/SKILL.md"));
    assertArrayEquals(root, reader.readSkillFile("my-pkg", "dev", "SKILL.md"));
  }

  /** 测试意图：相对路径原样交给归档读取器，百分号字面量与同名子目录都不做隐式解码。 */
  @Test
  void relativePathIsForwardedVerbatim() {
    SkillArchiveFileReader verbatimReader = mock(SkillArchiveFileReader.class);
    PlatformSkillContentReader verbatimContentReader =
        new PlatformSkillContentReader(skillPackageRepository, verbatimReader);

    UUID blobId = UUID.randomUUID();
    SkillPackage pkg =
        new SkillPackage(
            "my-pkg",
            "desc",
            "v1",
            blobId,
            List.of(new SkillManifestEntry("dev", "desc")),
            Instant.now(),
            0L,
            Instant.now(),
            Instant.now(),
            null);
    when(skillPackageRepository.getPackage("my-pkg")).thenReturn(pkg);
    byte[] literal = "literal".getBytes(StandardCharsets.UTF_8);
    when(verbatimReader.readFile(blobId, "dev/%2e%2e/literal%20.md")).thenReturn(literal);

    assertArrayEquals(
        literal, verbatimContentReader.readSkillFile("my-pkg", "dev", "%2e%2e/literal%20.md"));
    verify(verbatimReader).readFile(blobId, "dev/%2e%2e/literal%20.md");
  }

  /** 测试意图：所有无效相对路径在访问下游之前拒绝，无副作用。 */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "/SKILL.md", "sub\\file", "sub\nfile", ".", "..", "a//b", "a/"})
  void invalidRelativePathsHaveNoSideEffects(String path) {
    assertThrows(PlatformReadException.class, () -> reader.readSkillFile("my-pkg", "dev", path));
    verifyNoInteractions(skillPackageRepository, blobManager);
  }

  /** 测试意图：Package 和 Skill 名称的空值与空白校验不能触发下游调用。 */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void invalidNamesHaveNoSideEffects(String name) {
    assertThrows(PlatformReadException.class, () -> reader.readSkillFile(name, "dev", "SKILL.md"));
    assertThrows(
        PlatformReadException.class, () -> reader.readSkillFile("my-pkg", name, "SKILL.md"));
    verifyNoInteractions(skillPackageRepository, blobManager);
  }

  /** 测试意图：读取异常保留原始 cause。 */
  @Test
  void readFailurePreservesCause() {
    SkillArchiveFileReader mockReader = mock(SkillArchiveFileReader.class);
    PlatformSkillContentReader delegatedReader =
        new PlatformSkillContentReader(skillPackageRepository, mockReader);

    UUID blobId = UUID.randomUUID();
    SkillPackage pkg =
        new SkillPackage(
            "my-pkg",
            "desc",
            "v1",
            blobId,
            List.of(new SkillManifestEntry("dev", "desc")),
            Instant.now(),
            0L,
            Instant.now(),
            Instant.now(),
            null);
    when(skillPackageRepository.getPackage("my-pkg")).thenReturn(pkg);

    IllegalArgumentException cause = new IllegalArgumentException("custom read failure");
    when(mockReader.readFile(blobId, "dev/SKILL.md")).thenThrow(cause);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () -> delegatedReader.readSkillFile("my-pkg", "dev", "SKILL.md"));
    assertEquals("failed to read skill file: custom read failure", error.getMessage());
    assertSame(cause, error.getCause());
  }
}

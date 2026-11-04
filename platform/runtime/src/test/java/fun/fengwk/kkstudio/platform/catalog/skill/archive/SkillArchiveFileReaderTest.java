package fun.fengwk.kkstudio.platform.catalog.skill.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.InputEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.Type;
import fun.fengwk.kkstudio.platform.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** {@link SkillArchiveFileReader} 范围读取与校验的单元测试（使用内存 S3 假件）。 */
class SkillArchiveFileReaderTest {

  private StorageBlobManager blobManager;
  private InMemoryS3StorageService s3Storage;
  private SkillArchiveFileReader reader;

  @BeforeEach
  void setUp() {
    blobManager = mock(StorageBlobManager.class);
    s3Storage = new InMemoryS3StorageService();
    reader = new SkillArchiveFileReader(blobManager, s3Storage);
  }

  @Test
  void shouldReadFileExactBytesUsingRangeGet() throws IOException {
    byte[] skillMd =
        "---\nname: my-skill\ndescription: demo skill\n---\n".getBytes(StandardCharsets.UTF_8);
    byte[] script = "echo test\n".getBytes(StandardCharsets.UTF_8);

    SkillArchiveBuilder builder = new SkillArchiveBuilder();
    SkillArchiveBuilder.Result archive =
        builder.fromEntries(
            List.of(
                new InputEntry("my-skill/SKILL.md", Type.FILE, false, skillMd),
                new InputEntry("my-skill/run.sh", Type.FILE, true, script)));

    UUID blobId = UUID.randomUUID();
    s3Storage.putDirect(StorageObjectKeys.blobOriginal(blobId), archive.zip(), "application/zip");

    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setArchiveIndex(SkillArchiveIndex.from(archive.index()).encode());
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    byte[] readSkillMd = reader.readFile(blobId, "my-skill/SKILL.md");
    assertArrayEquals(skillMd, readSkillMd);

    byte[] readScript = reader.readFile(blobId, "my-skill/run.sh");
    assertArrayEquals(script, readScript);
  }

  @Test
  void shouldRejectWhenBlobUnavailableOrNotActive() {
    UUID blobId = UUID.randomUUID();
    when(blobManager.getBlob(blobId)).thenReturn(null);

    IllegalArgumentException notFound =
        assertThrows(
            IllegalArgumentException.class, () -> reader.readFile(blobId, "my-skill/SKILL.md"));
    assertEquals("archive blob is unavailable: " + blobId, notFound.getMessage());

    StorageBlob pendingBlob = new StorageBlob();
    pendingBlob.setId(blobId);
    pendingBlob.setState(StorageBlobState.DELETING);
    when(blobManager.getBlob(blobId)).thenReturn(pendingBlob);

    IllegalArgumentException deleting =
        assertThrows(
            IllegalArgumentException.class, () -> reader.readFile(blobId, "my-skill/SKILL.md"));
    assertEquals("archive blob is unavailable: " + blobId, deleting.getMessage());
  }

  @Test
  void shouldRejectWhenBlobHasNoArchiveIndex() {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setArchiveIndex(null);
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> reader.readFile(blobId, "my-skill/SKILL.md"));
    assertEquals("blob has no archive index: " + blobId, error.getMessage());
  }

  @Test
  void shouldRejectWhenEntryNotFoundInIndex() {
    UUID blobId = UUID.randomUUID();
    SkillArchiveIndex index =
        new SkillArchiveIndex(
            List.of(new SkillArchiveIndex.Entry("other/path.txt", 10L, 0L, 5L, "12345678")));
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setArchiveIndex(index.encode());
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> reader.readFile(blobId, "my-skill/SKILL.md"));
    assertEquals("archive entry not found: my-skill/SKILL.md", error.getMessage());
  }

  @Test
  void shouldRejectWhenContentMismatchOrCorrupted() throws IOException {
    byte[] skillMd =
        "---\nname: my-skill\ndescription: demo skill\n---\n".getBytes(StandardCharsets.UTF_8);
    SkillArchiveBuilder builder = new SkillArchiveBuilder();
    SkillArchiveBuilder.Result archive =
        builder.fromEntries(
            List.of(new InputEntry("my-skill/SKILL.md", Type.FILE, false, skillMd)));

    UUID blobId = UUID.randomUUID();
    // Put corrupted data in S3
    byte[] corrupted = archive.zip().clone();
    // Tamper with the compressed data bytes
    SkillArchiveBuilder.IndexEntry entry = archive.index().get(0);
    corrupted[(int) entry.dataOffset()] ^= 0xFF;
    s3Storage.putDirect(StorageObjectKeys.blobOriginal(blobId), corrupted, "application/zip");

    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setArchiveIndex(SkillArchiveIndex.from(archive.index()).encode());
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    assertThrows(UncheckedIOException.class, () -> reader.readFile(blobId, "my-skill/SKILL.md"));
  }

  @Test
  void shouldRejectTamperedArchiveIndexOffsetsSizesAndCrc() throws IOException {
    // 意图：索引被篡改为错误偏移、错误解压大小或错误 CRC 时都拒绝返回内容，绝不把损坏字节交给读取方。
    byte[] content =
        "---\nname: my-skill\ndescription: tamper target\n---\n".getBytes(StandardCharsets.UTF_8);
    SkillArchiveBuilder.Result archive =
        new SkillArchiveBuilder()
            .fromEntries(List.of(new InputEntry("my-skill/SKILL.md", Type.FILE, false, content)));

    UUID blobId = UUID.randomUUID();
    byte[] zip = archive.zip();
    s3Storage.putDirect(StorageObjectKeys.blobOriginal(blobId), zip, "application/zip");
    SkillArchiveIndex.Entry original = SkillArchiveIndex.from(archive.index()).entries().get(0);
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    // 偏移错误：范围读取从错误位置开始，inflate 失败或解压结果与摘要不符，两条路径都必须拒绝。
    blob.setArchiveIndex(
        new SkillArchiveIndex(
                List.of(
                    new SkillArchiveIndex.Entry(
                        original.path(),
                        original.size(),
                        original.dataOffset() + 1,
                        original.compressedSize(),
                        original.crc32())))
            .encode());
    assertThrows(RuntimeException.class, () -> reader.readFile(blobId, original.path()));

    // 解压大小错误：多声明一个字节，读取后的精确大小校验必须拒绝。
    blob.setArchiveIndex(
        new SkillArchiveIndex(
                List.of(
                    new SkillArchiveIndex.Entry(
                        original.path(),
                        original.size() + 1,
                        original.dataOffset(),
                        original.compressedSize(),
                        original.crc32())))
            .encode());
    IllegalStateException sizeError =
        assertThrows(IllegalStateException.class, () -> reader.readFile(blobId, original.path()));
    assertEquals("archive entry content mismatch: " + original.path(), sizeError.getMessage());

    // CRC 错误：内容大小相同也必须拒绝。
    String wrongCrc = original.crc32().equals("00000000") ? "ffffffff" : "00000000";
    blob.setArchiveIndex(
        new SkillArchiveIndex(
                List.of(
                    new SkillArchiveIndex.Entry(
                        original.path(),
                        original.size(),
                        original.dataOffset(),
                        original.compressedSize(),
                        wrongCrc)))
            .encode());
    IllegalStateException crcError =
        assertThrows(IllegalStateException.class, () -> reader.readFile(blobId, original.path()));
    assertEquals("archive entry content mismatch: " + original.path(), crcError.getMessage());
  }

  @Test
  void shouldRejectWhenEntryCrcMismatch() {
    UUID blobId = UUID.randomUUID();
    // Create an entry where declared CRC32 is wrong
    byte[] content = "test content".getBytes(StandardCharsets.UTF_8);
    // Deflate the content
    ByteArrayOutputStream deflated = new ByteArrayOutputStream();
    try (DeflaterOutputStream deflater =
        new DeflaterOutputStream(deflated, new Deflater(Deflater.DEFAULT_COMPRESSION, true))) {
      deflater.write(content);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    byte[] compressed = deflated.toByteArray();

    s3Storage.putDirect(StorageObjectKeys.blobOriginal(blobId), compressed, "application/zip");

    SkillArchiveIndex index =
        new SkillArchiveIndex(
            List.of(
                new SkillArchiveIndex.Entry(
                    "my-skill/SKILL.md",
                    content.length,
                    0L,
                    compressed.length,
                    "00000000"))); // Wrong CRC
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setState(StorageBlobState.ACTIVE);
    blob.setArchiveIndex(index.encode());
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> reader.readFile(blobId, "my-skill/SKILL.md"));
    assertEquals("archive entry content mismatch: my-skill/SKILL.md", error.getMessage());
  }
}

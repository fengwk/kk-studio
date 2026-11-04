package fun.fengwk.kkstudio.platform.catalog.skill.archive;

import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * 按归档索引范围读取单个文件：只对目标条目的压缩数据做一次 S3 Range GET，再解压该条目。
 *
 * <p>读取前校验 blob 是 ACTIVE 且带服务端生成的索引；解压后的字节数与 CRC32 必须与索引一致，否则拒绝返回。
 */
public class SkillArchiveFileReader {

  private final StorageBlobManager blobManager;
  private final S3StorageService s3StorageService;

  public SkillArchiveFileReader(StorageBlobManager blobManager, S3StorageService s3StorageService) {
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.s3StorageService = Objects.requireNonNull(s3StorageService, "s3StorageService");
  }

  /** 读取归档中的一个文件；条目不存在时抛出 {@link IllegalArgumentException}。 */
  public byte[] readFile(UUID artifactBlobId, String path) {
    StorageBlob blob = blobManager.getBlob(artifactBlobId);
    if (blob == null || blob.getState() != StorageBlobState.ACTIVE) {
      throw new IllegalArgumentException("archive blob is unavailable: " + artifactBlobId);
    }
    if (blob.getArchiveIndex() == null) {
      throw new IllegalArgumentException("blob has no archive index: " + artifactBlobId);
    }
    SkillArchiveIndex index = SkillArchiveIndex.decode(blob.getArchiveIndex());
    SkillArchiveIndex.Entry entry = index.find(path);
    if (entry == null) {
      throw new IllegalArgumentException("archive entry not found: " + path);
    }
    byte[] compressed =
        s3StorageService.readObjectRange(
            StorageObjectKeys.blobOriginal(artifactBlobId),
            entry.dataOffset(),
            entry.compressedSize());
    byte[] content = inflate(compressed, entry);
    CRC32 crc = new CRC32();
    crc.update(content);
    if (content.length != entry.size() || crc.getValue() != Long.parseLong(entry.crc32(), 16)) {
      throw new IllegalStateException("archive entry content mismatch: " + path);
    }
    return content;
  }

  private static byte[] inflate(byte[] compressed, SkillArchiveIndex.Entry entry) {
    try (InflaterInputStream input =
        new InflaterInputStream(new ByteArrayInputStream(compressed), new Inflater(true))) {
      return readBounded(input, entry.size(), entry.path());
    } catch (IOException error) {
      throw new UncheckedIOException("failed to inflate archive entry: " + entry.path(), error);
    }
  }

  private static byte[] readBounded(InputStream input, long limit, String path) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) != -1) {
      if (output.size() + (long) read > limit) {
        throw new IOException("archive entry exceeds its declared size: " + path);
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }
}

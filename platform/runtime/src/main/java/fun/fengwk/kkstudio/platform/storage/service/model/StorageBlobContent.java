package fun.fengwk.kkstudio.platform.storage.service.model;

import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 权威 Blob 内容读取结果。
 *
 * @author fengwk
 */
@Getter
public final class StorageBlobContent {

  private final UUID blobId;
  private final byte[] bytes;
  private final String mediaType;
  private final long sizeBytes;

  public StorageBlobContent(UUID blobId, byte[] bytes, String mediaType, long sizeBytes) {
    this.blobId = Objects.requireNonNull(blobId, "blobId");
    this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
    this.mediaType = mediaType;
    this.sizeBytes = sizeBytes;
  }

  public byte[] getBytes() {
    return Arrays.copyOf(bytes, bytes.length);
  }
}

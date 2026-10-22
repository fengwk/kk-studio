package fun.fengwk.kkstudio.canvas.infra;

import fun.fengwk.kkstudio.canvas.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Canvas 模块测试的 Blob 事实替身：只模拟 Core facts，原件 I/O 不参与事务 foundation。 */
public final class TestBlobAccess implements CanvasFunctionBlobAccess {

  private final Map<UUID, BlobFacts> facts = new ConcurrentHashMap<>();

  public void put(BlobFacts value) {
    facts.put(value.blobId(), value);
  }

  @Override
  public Optional<BlobFacts> findFacts(UUID blobId) {
    return Optional.ofNullable(facts.get(blobId));
  }

  @Override
  public CanvasFunctionResourceStream openOriginal(UUID blobId, long expectedSizeBytes) {
    throw new UnsupportedOperationException("foundation test does not open originals");
  }

  @Override
  public String originalUrl(UUID blobId, long expiresSeconds) {
    throw new UnsupportedOperationException("foundation test does not presign originals");
  }
}

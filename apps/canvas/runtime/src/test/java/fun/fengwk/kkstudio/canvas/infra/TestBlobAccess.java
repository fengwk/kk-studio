package fun.fengwk.kkstudio.canvas.infra;

import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import fun.fengwk.kkstudio.canvas.BlobFacts;
import fun.fengwk.kkstudio.canvas.infra.platform.PlatformCanvasFunctionBlobAccess;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Canvas 模块测试的 Blob 事实替身：原件 I/O 不参与事务 foundation。 */
public final class TestBlobAccess {

  private final Map<UUID, BlobFacts> facts = new ConcurrentHashMap<>();
  private final PlatformCanvasFunctionBlobAccess access =
      mock(
          PlatformCanvasFunctionBlobAccess.class,
          invocation -> {
            switch (invocation.getMethod().getName()) {
              case "findFacts":
                return Optional.ofNullable(facts.get(invocation.getArgument(0, UUID.class)));
              case "openOriginal":
                throw new UnsupportedOperationException("foundation test does not open originals");
              case "originalUrl":
                throw new UnsupportedOperationException(
                    "foundation test does not presign originals");
              default:
                return RETURNS_DEFAULTS.answer(invocation);
            }
          });

  public void put(BlobFacts value) {
    facts.put(value.blobId(), value);
  }

  public PlatformCanvasFunctionBlobAccess access() {
    return access;
  }
}

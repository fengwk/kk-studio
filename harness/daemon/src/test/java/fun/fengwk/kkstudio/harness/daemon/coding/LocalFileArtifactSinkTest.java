package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Local file artifact sink 的 store / read / path-boundary 契约测试。 */
class LocalFileArtifactSinkTest {

  @TempDir Path environmentRoot;

  /** store + read 必须完整 round-trip 字节。 */
  @Test
  void storeAndReadRoundTripBytes() throws IOException {
    Path directory = environmentRoot.resolve("artifacts");
    LocalFileArtifactSink sink = new LocalFileArtifactSink(directory);
    byte[] data = new byte[] {1, 2, 3, 4, 5};

    ArtifactRef ref = sink.store(data, "application/octet-stream");
    assertArrayEquals(data, sink.read(ref));
    assertArrayEquals(data, Files.readAllBytes(directory.resolve(ref.artifactId())));

    ArtifactRef wrongSize = new ArtifactRef(ref.artifactId(), ref.mediaType(), ref.sizeBytes() + 1);
    assertThrows(IOException.class, () -> sink.read(wrongSize));
  }

  /** 包含 {@code ..} 的 artifact id 必须被拒绝，不得越出 artifact 目录。 */
  @Test
  void readRejectsTraversalArtifactId() throws IOException {
    LocalFileArtifactSink sink = new LocalFileArtifactSink(environmentRoot.resolve("artifacts"));

    ArtifactRef traversal = new ArtifactRef("..", "application/octet-stream", 0);
    assertEscape(sink, traversal);

    ArtifactRef nested = new ArtifactRef("foo/../bar", "application/octet-stream", 0);
    assertEscape(sink, nested);

    ArtifactRef backslash = new ArtifactRef("..\\bar", "text/plain", 0);
    assertEscape(sink, backslash);

    ArtifactRef absolute = new ArtifactRef("/etc/passwd", "text/plain", 0);
    assertEscape(sink, absolute);
  }

  /** 包含非法字符或路径段的 artifact id 必须被拒绝。 */
  @Test
  void readRejectsInvalidArtifactIdCharacters() throws IOException {
    LocalFileArtifactSink sink = new LocalFileArtifactSink(environmentRoot.resolve("artifacts"));
    ArtifactRef dot = new ArtifactRef(".", "text/plain", 0);
    assertThrows(IOException.class, () -> sink.read(dot));

    ArtifactRef slash = new ArtifactRef("foo/bar", "text/plain", 0);
    assertThrows(IOException.class, () -> sink.read(slash));
  }

  /** 已存在但指向目录外的符号链接也必须拒绝，不能通过单段 id 绕过 canonical 边界。 */
  @Test
  void readRejectsSymlinkOutsideArtifactDirectory() throws IOException {
    Path directory = environmentRoot.resolve("artifacts");
    LocalFileArtifactSink sink = new LocalFileArtifactSink(directory);
    Files.createDirectories(directory);
    Path outside = environmentRoot.resolve("outside-artifact");
    Files.write(outside, new byte[] {1});
    Files.createSymbolicLink(directory.resolve("outside-link"), outside);

    ArtifactRef ref = new ArtifactRef("outside-link", "application/octet-stream", 1);
    assertThrows(IOException.class, () -> sink.read(ref));
  }

  /** 未存储的 artifact id 必须抛出 IOException，不能返回 null 或 silent。 */
  @Test
  void readMissingArtifactRaisesIOException() throws IOException {
    LocalFileArtifactSink sink = new LocalFileArtifactSink(environmentRoot.resolve("artifacts"));
    ArtifactRef missing = new ArtifactRef("does-not-exist", "text/plain", 0);
    assertThrows(IOException.class, () -> sink.read(missing));
  }

  private static void assertEscape(LocalFileArtifactSink sink, ArtifactRef ref) {
    assertThrows(IOException.class, () -> sink.read(ref));
  }
}

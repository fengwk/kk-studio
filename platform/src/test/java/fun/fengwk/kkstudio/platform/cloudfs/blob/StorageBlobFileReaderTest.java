package fun.fengwk.kkstudio.platform.cloudfs.blob;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemValidationException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 验证 {@link StorageBlobFileReader} 的 Blob 内容读取、严格 UTF-8 解码、尺寸硬防护与媒体类型判定行为。 */
class StorageBlobFileReaderTest {

  private StorageBlobContentService mockContentService;
  private StorageBlobFileReader reader;

  @BeforeEach
  void setUp() {
    mockContentService = mock(StorageBlobContentService.class);
    reader = new StorageBlobFileReader(mockContentService);
  }

  @Test
  void readTextBlobSuccessfully() {
    // 意图：明确文本媒体类型应严格解码为 UTF-8 文本结果
    UUID blobId = UUID.randomUUID();
    byte[] textBytes = "hello cloud blob".getBytes(StandardCharsets.UTF_8);
    when(mockContentService.readBlobContent(eq(blobId), anyLong()))
        .thenReturn(new StorageBlobContent(blobId, textBytes, "text/plain", textBytes.length));

    BlobReadResult result = reader.read(blobId);

    BlobReadResult.Text textResult = assertInstanceOf(BlobReadResult.Text.class, result);
    assertEquals("hello cloud blob", textResult.text());
    assertEquals("text/plain", textResult.mediaType());
    assertEquals(textBytes.length, textResult.sizeBytes());
  }

  @Test
  void readBinaryBlobSuccessfully() {
    // 意图：图片等二进制媒体类型应保留原始字节并返回 Binary 结果
    UUID blobId = UUID.randomUUID();
    byte[] imageBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
    when(mockContentService.readBlobContent(eq(blobId), anyLong()))
        .thenReturn(
            new StorageBlobContent(
                blobId, imageBytes, "IMAGE/PNG; charset=binary", imageBytes.length));

    BlobReadResult result = reader.read(blobId);

    BlobReadResult.Binary binaryResult = assertInstanceOf(BlobReadResult.Binary.class, result);
    assertArrayEquals(imageBytes, binaryResult.bytes());
    assertEquals(
        "image/png", binaryResult.mediaType(), "Media type must be normalized to canonical form");
    assertEquals(imageBytes.length, binaryResult.sizeBytes());
  }

  @Test
  void malformedUtf8BytesInTextTypeThrowsValidationException() {
    // 意图：声明为文本但包含畸形 UTF-8 字节时必须严格失败，杜绝替换字符
    UUID blobId = UUID.randomUUID();
    byte[] invalidBytes = new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00};
    when(mockContentService.readBlobContent(eq(blobId), anyLong()))
        .thenReturn(
            new StorageBlobContent(blobId, invalidBytes, "text/plain", invalidBytes.length));

    assertThrows(CloudFileSystemValidationException.class, () -> reader.read(blobId));
  }

  @Test
  void exceedingBlobLimitFromServiceThrowsValidationException() {
    // 意图：底层返回超出上限的 IllegalArgumentException 应转换为校验异常
    UUID blobId = UUID.randomUUID();
    when(mockContentService.readBlobContent(eq(blobId), anyLong()))
        .thenThrow(new IllegalArgumentException("blob too big"));

    assertThrows(CloudFileSystemValidationException.class, () -> reader.read(blobId));
  }

  @Test
  void defensiveSizeGuardRejectsOversizedContentEvenIfReturnedByService() {
    // 意图：即使底层 service 返回了超限内容，reader 自身必须执行二次硬校验防护
    UUID blobId = UUID.randomUUID();
    byte[] oversizedBytes = new byte[100];
    when(mockContentService.readBlobContent(eq(blobId), anyLong()))
        .thenReturn(new StorageBlobContent(blobId, oversizedBytes, "text/plain", 100));

    // 指定上限 50 字节
    assertThrows(CloudFileSystemValidationException.class, () -> reader.read(blobId, 50));
  }

  @Test
  void isTextMediaTypeIdentifiesTextFormatsAndCharsets() {
    // 意图：正确识别 text/*、json 等格式，且拒绝显式非 UTF-8 charset
    assertTrue(StorageBlobFileReader.isTextMediaType("text/plain"));
    assertTrue(StorageBlobFileReader.isTextMediaType("text/markdown; charset=utf-8"));
    assertTrue(StorageBlobFileReader.isTextMediaType("text/markdown; charset=\"UTF-8\""));
    assertTrue(StorageBlobFileReader.isTextMediaType("application/json"));
    assertTrue(StorageBlobFileReader.isTextMediaType("application/problem+json"));
    assertTrue(StorageBlobFileReader.isTextMediaType("application/xml"));
    assertTrue(StorageBlobFileReader.isTextMediaType("application/soap+xml"));
    assertTrue(StorageBlobFileReader.isTextMediaType("application/javascript"));
    assertTrue(StorageBlobFileReader.isTextMediaType("application/typescript"));
    assertTrue(StorageBlobFileReader.isTextMediaType("application/x-sh"));
    assertTrue(StorageBlobFileReader.isTextMediaType("application/yaml"));

    // 非 UTF-8 charset 必须被拒绝为文本
    assertFalse(StorageBlobFileReader.isTextMediaType("text/plain; charset=gbk"));
    assertFalse(StorageBlobFileReader.isTextMediaType("text/plain; charset=iso-8859-1"));

    // 二进制格式
    assertFalse(StorageBlobFileReader.isTextMediaType("image/png"));
    assertFalse(StorageBlobFileReader.isTextMediaType("application/octet-stream"));
    assertFalse(StorageBlobFileReader.isTextMediaType(null));
    assertFalse(StorageBlobFileReader.isTextMediaType(""));
  }

  @Test
  void nullOrInvalidMediaTypeFallsBackToOctetStreamBinary() {
    // 意图：当 mediaType 为 null 或非法时回退到 application/octet-stream 并返回 Binary
    UUID blobId = UUID.randomUUID();
    byte[] bytes = new byte[] {1, 2, 3};
    when(mockContentService.readBlobContent(eq(blobId), anyLong()))
        .thenReturn(new StorageBlobContent(blobId, bytes, null, bytes.length));

    BlobReadResult result = reader.read(blobId, -1);
    BlobReadResult.Binary binaryResult = assertInstanceOf(BlobReadResult.Binary.class, result);
    assertEquals("application/octet-stream", binaryResult.mediaType());

    assertEquals(
        "application/octet-stream",
        StorageBlobFileReader.normalizeCanonicalMediaType("invalid-without-slash"));
  }

  @Test
  void corruptBlobSizeMismatchThrowsValidationException() {
    // 意图：当底层 storage_blob 记录的 sizeBytes 与实际字节数组长度不一致时判定为损坏，必须拒绝
    UUID blobId = UUID.randomUUID();
    when(mockContentService.readBlobContent(eq(blobId), anyLong()))
        .thenReturn(new StorageBlobContent(blobId, new byte[] {1, 2, 3}, "text/plain", 10));

    CloudFileSystemValidationException ex =
        assertThrows(CloudFileSystemValidationException.class, () -> reader.read(blobId));
    assertTrue(ex.getMessage().contains("Corrupt blob: declared sizeBytes"));
  }

  @Test
  void testBinaryDefensiveCopyAndSemanticValue() {
    // 意图：验证 Binary 记录类具备严格防御性复制（入参及出参均不可逃逸修改），并按语义值进行等价性与哈希判定
    byte[] source = new byte[] {1, 2, 3};
    BlobReadResult.Binary b1 = new BlobReadResult.Binary(source, "image/png", 3);
    BlobReadResult.Binary b2 = new BlobReadResult.Binary(new byte[] {1, 2, 3}, "image/png", 3);
    BlobReadResult.Binary b3 = new BlobReadResult.Binary(new byte[] {1, 2, 4}, "image/png", 3);

    // 验证入参修改防御性隔离
    source[0] = 99;
    assertEquals(
        1, b1.bytes()[0], "External modification of source array must not mutate Binary state");

    // 验证出参修改防御性隔离
    byte[] extracted = b1.bytes();
    extracted[0] = 88;
    assertEquals(1, b1.bytes()[0], "Modification of returned array must not mutate Binary state");

    // 语义值比对
    assertEquals(b1, b2);
    assertEquals(b1.hashCode(), b2.hashCode());
    assertFalse(b1.equals(b3));
    assertEquals(16 * 1024 * 1024L, StorageBlobFileReader.MAX_BLOB_READ_BYTES);
  }
}

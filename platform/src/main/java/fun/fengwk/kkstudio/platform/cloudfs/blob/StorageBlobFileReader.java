package fun.fengwk.kkstudio.platform.cloudfs.blob;

import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemValidationException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 负责安全、有界地读取底层 StorageBlob 内容并按媒体类型识别为文本或二进制。
 *
 * <p>单文件硬上限为 16 MiB。明确的 UTF-8 文本类型使用严格解码器校验并解码；其他媒体类型保留原始字节并归一化为规范媒体类型。
 */
public class StorageBlobFileReader {

  public static final long MAX_BLOB_READ_BYTES = 16 * 1024 * 1024L;

  private final StorageBlobContentService blobContentService;

  public StorageBlobFileReader(StorageBlobContentService blobContentService) {
    this.blobContentService = Objects.requireNonNull(blobContentService, "blobContentService");
  }

  /**
   * 安全读取指定的 storage_blob。
   *
   * @param blobId blob UUID
   * @return 解码后的文本或二进制结果
   * @throws CloudFileSystemValidationException 当内容超出上限或标为文本但非严格 UTF-8 时抛出
   */
  public BlobReadResult read(UUID blobId) {
    return read(blobId, MAX_BLOB_READ_BYTES);
  }

  /**
   * 安全读取指定的 storage_blob，指定最大允许读取字节数。
   *
   * @param blobId blob UUID
   * @param maxSizeBytes 最大允许读取字节数
   * @return 解码后的文本或二进制结果
   * @throws CloudFileSystemValidationException 当内容超出上限或标为文本但非严格 UTF-8 时抛出
   */
  public BlobReadResult read(UUID blobId, long maxSizeBytes) {
    Objects.requireNonNull(blobId, "blobId");
    long effectiveMax =
        maxSizeBytes <= 0 ? MAX_BLOB_READ_BYTES : Math.min(maxSizeBytes, MAX_BLOB_READ_BYTES);

    StorageBlobContent content;
    try {
      content = blobContentService.readBlobContent(blobId, effectiveMax);
    } catch (IllegalArgumentException e) {
      throw new CloudFileSystemValidationException(
          "Blob size exceeds readable limit of " + effectiveMax + " bytes", e);
    }

    if (content == null) {
      throw new CloudFileSystemValidationException("Blob content not found: " + blobId);
    }

    // 防御性校验实际字节尺寸，杜绝底层 mock 或实现未遵守上限
    if (content.getSizeBytes() > effectiveMax
        || (content.getBytes() != null && content.getBytes().length > effectiveMax)) {
      throw new CloudFileSystemValidationException(
          "Blob size exceeds readable limit of " + effectiveMax + " bytes");
    }

    // 校验 declared sizeBytes 与实际字节数组长度一致，不一致判定为损坏并拒绝
    if (content.getBytes() == null || content.getSizeBytes() != content.getBytes().length) {
      throw new CloudFileSystemValidationException(
          "Corrupt blob: declared sizeBytes ("
              + content.getSizeBytes()
              + ") does not match byte array length ("
              + (content.getBytes() == null ? 0 : content.getBytes().length)
              + ")");
    }

    String rawMediaType = content.getMediaType();
    String canonicalMediaType = normalizeCanonicalMediaType(rawMediaType);

    if (isTextMediaType(rawMediaType)) {
      String text = strictDecode(content.getBytes(), "blob text content");
      return new BlobReadResult.Text(text, canonicalMediaType, content.getSizeBytes());
    }

    return new BlobReadResult.Binary(
        content.getBytes(), canonicalMediaType, content.getSizeBytes());
  }

  /** 严格按照 UTF-8 规范解码字节数组，遇到畸形字节或未映射字符抛出异常。 */
  public static String strictDecode(byte[] bytes, String errorContext) {
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(errorContext, "errorContext");
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      CharBuffer charBuffer = decoder.decode(ByteBuffer.wrap(bytes));
      return charBuffer.toString();
    } catch (CharacterCodingException e) {
      throw new CloudFileSystemValidationException(
          "Invalid UTF-8 byte sequence in " + errorContext, e);
    }
  }

  /** 判断是否属于明确的 UTF-8 文本媒体类型。包含显式非 UTF-8 charset 的文本类型将被拒绝为文本。 */
  public static boolean isTextMediaType(String mediaType) {
    if (mediaType == null || mediaType.isBlank()) {
      return false;
    }
    String trimmed = mediaType.trim().toLowerCase(Locale.ROOT);
    String[] parts = trimmed.split(";");
    String baseType = parts[0].trim();

    // 若显式声明了 charset，必须为 utf-8 / utf8
    for (int i = 1; i < parts.length; i++) {
      String param = parts[i].trim();
      if (param.startsWith("charset=")) {
        String charset = param.substring("charset=".length()).trim();
        if (charset.startsWith("\"") && charset.endsWith("\"") && charset.length() >= 2) {
          charset = charset.substring(1, charset.length() - 1).trim();
        }
        if (!charset.equals("utf-8") && !charset.equals("utf8")) {
          return false;
        }
      }
    }

    if (baseType.startsWith("text/")) {
      return true;
    }
    return baseType.equals("application/json")
        || baseType.equals("application/xml")
        || baseType.endsWith("+json")
        || baseType.endsWith("+xml")
        || baseType.equals("application/javascript")
        || baseType.equals("application/typescript")
        || baseType.equals("application/x-sh")
        || baseType.equals("application/x-yaml")
        || baseType.equals("application/yaml");
  }

  /** 将任意 mediaType 归一化为符合 ToolResult 规范的小写无参数 canonical mediaType。 */
  public static String normalizeCanonicalMediaType(String mediaType) {
    if (mediaType == null || mediaType.isBlank()) {
      return "application/octet-stream";
    }
    String base = mediaType.trim().toLowerCase(Locale.ROOT);
    int semicolonIndex = base.indexOf(';');
    if (semicolonIndex >= 0) {
      base = base.substring(0, semicolonIndex).trim();
    }
    int slashIndex = base.indexOf('/');
    if (slashIndex > 0 && slashIndex < base.length() - 1 && base.indexOf('/', slashIndex + 1) < 0) {
      String type = base.substring(0, slashIndex);
      String subType = base.substring(slashIndex + 1);
      if (isValidMediaTypeToken(type) && isValidMediaTypeToken(subType)) {
        return base;
      }
    }
    return "application/octet-stream";
  }

  private static boolean isValidMediaTypeToken(String token) {
    if (token == null || token.isEmpty()) {
      return false;
    }
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (!isTokenChar(c)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isTokenChar(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '!'
        || c == '#'
        || c == '$'
        || c == '&'
        || c == '^'
        || c == '_'
        || c == '.'
        || c == '+'
        || c == '-';
  }
}

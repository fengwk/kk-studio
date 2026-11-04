package fun.fengwk.kkstudio.platform.storage.service;

import org.springframework.util.Assert;
import org.springframework.util.MimeTypeUtils;

import fun.fengwk.kkstudio.platform.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;

import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** 上传输入与已上传对象的完整性校验；不执行存储 I/O。 */
final class StorageUploadValidation {

  private StorageUploadValidation() {}

  static String validateFilename(String filename) {
    Assert.hasText(filename, "filename must not be blank");
    Assert.isTrue(filename.length() <= 512, "filename must not exceed 512 characters");
    return filename;
  }

  static long validateSize(Long sizeBytes) {
    Assert.isTrue(sizeBytes != null, "sizeBytes must not be null");
    Assert.isTrue(sizeBytes >= 0L, "sizeBytes must be greater than or equal to 0");
    return sizeBytes;
  }

  static String validateSha256(String sha256) {
    Assert.hasText(sha256, "sha256 must not be blank");
    String normalized = sha256.trim().toLowerCase(Locale.ROOT);
    Assert.isTrue(
        normalized.matches("[0-9a-f]{64}"), "sha256 must be a 64-character hexadecimal digest");
    return normalized;
  }

  static String normalizeMediaType(String mediaType) {
    Assert.hasText(mediaType, "mediaType must not be blank");
    for (int i = 0; i < mediaType.length(); i++) {
      Assert.isTrue(
          !Character.isISOControl(mediaType.charAt(i)),
          "mediaType must not contain control characters");
    }
    String normalized = mediaType.trim();
    Assert.isTrue(normalized.length() <= 255, "mediaType must not exceed 255 characters");
    try {
      return MimeTypeUtils.parseMimeType(normalized).toString();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("mediaType must be a valid media type", e);
    }
  }

  static byte[] decodeHex(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      int high = Character.digit(hex.charAt(i * 2), 16);
      int low = Character.digit(hex.charAt(i * 2 + 1), 16);
      bytes[i] = (byte) ((high << 4) | low);
    }
    return bytes;
  }

  static void verifyObject(S3ObjectMetadata head, StorageUpload upload) {
    if (head.contentLength() != upload.getDeclaredSize()) {
      throw new StorageVerificationException(
          "upload "
              + upload.getId()
              + " size mismatch: declared "
              + upload.getDeclaredSize()
              + " but object has "
              + head.contentLength());
    }
    byte[] expected = decodeHex(upload.getDeclaredSha256());
    byte[] actual;
    try {
      actual = Base64.getDecoder().decode(head.checksumSha256());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new StorageVerificationException(
          "upload " + upload.getId() + " checksum unavailable or malformed");
    }
    if (!Arrays.equals(expected, actual)) {
      throw new StorageVerificationException("upload " + upload.getId() + " checksum mismatch");
    }
  }
}

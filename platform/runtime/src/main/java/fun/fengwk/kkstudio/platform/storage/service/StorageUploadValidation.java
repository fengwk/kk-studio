package fun.fengwk.kkstudio.platform.storage.service;

import org.springframework.util.MimeTypeUtils;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.storage.s3.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;

import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** 上传输入与已上传对象的完整性校验；不执行存储 I/O。 */
final class StorageUploadValidation {

  private StorageUploadValidation() {}

  static String validateFilename(String filename) {
    if (filename == null || filename.isBlank()) {
      throw DomainException.validation("filename", "filename must not be blank");
    }
    if (filename.length() > 512) {
      throw DomainException.validation("filename", "filename must not exceed 512 characters");
    }
    return filename;
  }

  static long validateSize(Long sizeBytes) {
    if (sizeBytes == null) {
      throw DomainException.validation("sizeBytes", "sizeBytes must not be null");
    }
    if (sizeBytes < 0L) {
      throw DomainException.validation("sizeBytes", "sizeBytes must be greater than or equal to 0");
    }
    return sizeBytes;
  }

  static String validateSha256(String sha256) {
    if (sha256 == null || sha256.isBlank()) {
      throw DomainException.validation("sha256", "sha256 must not be blank");
    }
    String normalized = sha256.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[0-9a-f]{64}")) {
      throw DomainException.validation(
          "sha256", "sha256 must be a 64-character hexadecimal digest");
    }
    return normalized;
  }

  static String normalizeMediaType(String mediaType) {
    if (mediaType == null || mediaType.isBlank()) {
      throw DomainException.validation("mediaType", "mediaType must not be blank");
    }
    for (int i = 0; i < mediaType.length(); i++) {
      if (Character.isISOControl(mediaType.charAt(i))) {
        throw DomainException.validation(
            "mediaType", "mediaType must not contain control characters");
      }
    }
    String normalized = mediaType.trim();
    if (normalized.length() > 255) {
      throw DomainException.validation("mediaType", "mediaType must not exceed 255 characters");
    }
    try {
      return MimeTypeUtils.parseMimeType(normalized).toString();
    } catch (IllegalArgumentException e) {
      throw DomainException.validation("mediaType", "mediaType must be a valid media type", e);
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
      throw DomainException.conflict(
          "STORAGE_VERIFICATION",
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
      throw DomainException.conflict(
          "STORAGE_VERIFICATION",
          "upload " + upload.getId() + " checksum unavailable or malformed");
    }
    if (!Arrays.equals(expected, actual)) {
      throw DomainException.conflict(
          "STORAGE_VERIFICATION", "upload " + upload.getId() + " checksum mismatch");
    }
  }
}

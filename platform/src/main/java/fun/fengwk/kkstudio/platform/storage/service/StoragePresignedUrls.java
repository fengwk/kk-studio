package fun.fengwk.kkstudio.platform.storage.service;

import fun.fengwk.kkstudio.share.storage.S3PresignedResponseDTO;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

/** 预签名响应到公开 DTO 的映射：刻意丢弃 bucket 与对象物理 key。 */
public final class StoragePresignedUrls {

  private StoragePresignedUrls() {}

  public static StoragePresignedUrlDTO from(S3PresignedResponseDTO signed) {
    return StoragePresignedUrlDTO.builder()
        .method(signed.getMethod())
        .url(signed.getUrl())
        .headers(signed.getHeaders())
        .expiresAt(signed.getExpiresAt())
        .build();
  }
}

package fun.fengwk.kkstudio.platform.storage.service;

import fun.fengwk.kkstudio.platform.storage.S3PresignedUrl;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

/** 预签名对象到公开 DTO 的映射：向调用方暴露标准化传输字段。 */
public final class StoragePresignedUrls {

  private StoragePresignedUrls() {}

  public static StoragePresignedUrlDTO from(S3PresignedUrl signed) {
    return StoragePresignedUrlDTO.builder()
        .method(signed.getMethod())
        .url(signed.getUrl())
        .headers(signed.getHeaders())
        .expiresAt(signed.getExpiresAt())
        .build();
  }
}

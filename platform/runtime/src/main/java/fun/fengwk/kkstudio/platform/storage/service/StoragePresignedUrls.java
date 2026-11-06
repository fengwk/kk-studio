package fun.fengwk.kkstudio.platform.storage.service;

import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUrlMethod;
import fun.fengwk.kkstudio.platform.storage.s3.S3PresignedUrl;

/** 预签名对象到公开 DTO 的映射：向调用方暴露标准化传输字段。 */
public final class StoragePresignedUrls {

  private StoragePresignedUrls() {}

  public static StoragePresignedUrlDTO from(S3PresignedUrl signed) {
    return StoragePresignedUrlDTO.builder()
        .method(StorageUrlMethod.valueOf(signed.getMethod()))
        .url(signed.getUrl())
        .headers(signed.getHeaders())
        .expiresAt(signed.getExpiresAt())
        .build();
  }
}

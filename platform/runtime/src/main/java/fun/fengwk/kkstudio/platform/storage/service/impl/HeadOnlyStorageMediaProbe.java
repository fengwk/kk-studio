package fun.fengwk.kkstudio.platform.storage.service.impl;

import org.springframework.util.StringUtils;

import fun.fengwk.kkstudio.platform.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.service.StorageMediaProbe;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageMediaFacts;

/**
 * 默认媒体事实探针：仅记录 checksum mode HEAD 返回的媒体类型（空白时回退 {@code application/octet-stream}），尺寸与时长留空。
 *
 * <p>后续 Canvas 媒体集成应提供基于 Ffmpeg 的实现（经 {@code s3Key} 下载内容分析），并替换本默认 bean； 本基础不感知媒体实现细节。
 *
 * @author fengwk
 */
public class HeadOnlyStorageMediaProbe implements StorageMediaProbe {

  static final String FALLBACK_MEDIA_TYPE = "application/octet-stream";

  @Override
  public StorageMediaFacts probe(String s3Key, S3ObjectMetadata headMetadata) {
    String mediaType =
        StringUtils.hasText(headMetadata.contentType())
            ? headMetadata.contentType()
            : FALLBACK_MEDIA_TYPE;
    return new StorageMediaFacts(mediaType, null, null, null);
  }
}

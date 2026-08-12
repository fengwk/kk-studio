package fun.fengwk.kkstudio.core.storage.service;

import fun.fengwk.kkstudio.core.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.core.storage.service.model.StorageMediaFacts;

/**
 * 媒体事实探针（可插拔，不耦合任何具体媒体实现）。
 *
 * <p>complete 时由服务调用，返回权威的 {@link StorageMediaFacts} 写入 blob 的不可变事实列。 默认实现只记录 HEAD 返回的媒体类型与大小，
 * 维度/时长留空；后续 Canvas 媒体集成可以提供基于 Ffmpeg 的实现（通过 {@code s3Key} 下载内容分析）， 本基础不感知其细节。
 */
public interface StorageMediaProbe {

  /**
   * 探测对象的权威媒体事实。
   *
   * @param s3Key 对象确定性键（可据此下载内容做深度分析）
   * @param headMetadata 服务已完成的 checksum mode HEAD 元数据（含大小与媒体类型）
   * @return 权威媒体事实；探针失败时抛出异常使 complete 失败（上传保持 PENDING 可重试）
   */
  StorageMediaFacts probe(String s3Key, S3ObjectMetadata headMetadata);
}

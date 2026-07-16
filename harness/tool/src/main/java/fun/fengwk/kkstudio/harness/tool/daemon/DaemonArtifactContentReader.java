package fun.fengwk.kkstudio.harness.tool.daemon;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;

/**
 * Cloud Gateway 接收端保存 wire artifact bytes 并返回新 global {@link ArtifactRef} 的 SPI。
 *
 * <p>实现负责把 {@code contentBase64} 解码后的字节持久化到自己的 immutable ArtifactStore，并返回具有全局 id 的新 ref，使 Daemon
 * 本地 ref 被替换为 receiver 的全局 ref；该 ref 必须随终态 payload 重放自包含，不依赖连接内内存映射。
 */
@FunctionalInterface
public interface DaemonArtifactContentReader {

  /**
   * 持久化 wire artifact 字节，并返回与 {@code mediaType} / {@code sizeBytes} 匹配的全局 ref。
   *
   * @param mediaType 来自 wire 的 mediaType；不允许 blank。
   * @param sizeBytes 来自 wire 的 sizeBytes；必须等于 {@code bytes.length}（codec 已校验）。
   * @param bytes artifact 完整字节；caller 不再持有该数组所有权。
   * @return 新 ref；其 {@link ArtifactRef#artifactId()} 必须与 wire 中可能不同的本地 id 解耦。
   */
  ArtifactRef store(String mediaType, long sizeBytes, byte[] bytes);
}

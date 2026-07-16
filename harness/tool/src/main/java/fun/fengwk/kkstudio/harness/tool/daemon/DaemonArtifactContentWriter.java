package fun.fengwk.kkstudio.harness.tool.daemon;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import java.io.IOException;

/**
 * Daemon 发端读取本地 artifact bytes 并写入 wire 的 SPI。
 *
 * <p>codec 不依赖 {@code harness/daemon}，由 Daemon Runtime 提供该实现以把本地 {@link ArtifactRef} 解析为字节再交给 codec
 * 编码。
 */
@FunctionalInterface
public interface DaemonArtifactContentWriter {

  /**
   * 读取本地 artifact 字节。
   *
   * @return artifact 完整字节；长度必须等于 {@code ref.sizeBytes()}。
   * @throws IOException 当 artifact 不可读或被删除时；codec 会将异常包装为 {@link DaemonProtocolException} 并由
   *     Runtime 收敛为 FAILED。
   */
  byte[] readBytes(ArtifactRef ref) throws IOException;
}

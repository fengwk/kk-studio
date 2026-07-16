package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import java.io.IOException;

/**
 * 读取本地已存储 artifact 字节的 SPI，与 {@link ArtifactSink} 配套使用。
 *
 * <p>codec 在编码 artifact wire content 时通过该接口读取 bytes，使 {@link ArtifactRef} 携带的真实内容能随终态 payload
 * 自包含；实现必须 保证 {@code ref.sizeBytes()} 等于返回字节数组长度。
 *
 * <p>为不破坏既有 {@link ArtifactSink} 公共契约，作为 sibling interface 由实现类自行选择实现。
 */
public interface ArtifactSource {

  /**
   * 读取指定 ref 的完整字节。
   *
   * @param ref 已由同一组件 {@link ArtifactSink#store(byte[], String)} 写入的 artifact 引用。
   * @return artifact 完整字节的不可变副本；长度为 {@code ref.sizeBytes()}。
   * @throws IOException 当 ref 不可解析、对应文件/对象不存在或越出存储边界时。
   */
  byte[] read(ArtifactRef ref) throws IOException;
}

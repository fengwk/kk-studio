package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.tool.ResourceRef;

import java.io.IOException;

/**
 * 读取本地已存储 resource 字节的 SPI，与 {@link ResourceSink} 配套使用。
 *
 * <p>codec 在编码 resource wire content 时通过该接口读取 bytes，使 {@link ResourceRef} 携带的真实内容能随终态 payload
 * 自包含；实现只读取自己生成/拥有的 ref，并必须保证返回字节与声明 size/sha 一致。
 */
public interface ResourceSource {

  /**
   * 读取指定 ref 的完整字节。
   *
   * @param ref 已由同一组件 {@link ResourceSink#store(byte[], String)} 写入的 resource 引用。
   * @return resource 完整字节的不可变副本；长度与摘要必须等于 {@code ref.size()} / {@code ref.sha256()}（当声明时）。
   * @throws IOException 当 ref 不可解析、对应文件/对象不存在或越出存储边界时。
   */
  byte[] read(ResourceRef ref) throws IOException;
}

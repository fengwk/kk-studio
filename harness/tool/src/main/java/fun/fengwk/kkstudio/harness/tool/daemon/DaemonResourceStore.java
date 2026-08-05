package fun.fengwk.kkstudio.harness.tool.daemon;

import fun.fengwk.kkstudio.harness.tool.ResourceRef;

import java.io.IOException;

/**
 * Daemon 发端读写本地 resource bytes 的 SPI。
 *
 * <p>codec 不依赖 {@code harness/daemon}，由 Daemon Runtime 提供实现（通常是 {@code LocalFileResourceStore}）：编码
 * {@link fun.fengwk.kkstudio.harness.tool.ResourceToolContent} 时通过 {@link #read} 取回字节并复核 size/sha；
 * 编码 {@link fun.fengwk.kkstudio.harness.tool.BinaryToolContent} 时先经 {@link #store} 落盘再编码返回的
 * resource 引用。
 */
public interface DaemonResourceStore {

  /**
   * 持久化字节并返回规范 ResourceRef。
   *
   * @return 指向已写入字节的引用；file resource 必须携带非空 size/sha。
   * @throws IOException 当字节不可写入时；codec 会将异常包装为 {@link DaemonProtocolException}。
   */
  ResourceRef store(byte[] bytes, String mediaType) throws IOException;

  /**
   * 读取本地 resource 字节。
   *
   * @return resource 完整字节；实现必须保证长度与 {@code ref.size()}、摘要与 {@code ref.sha256()} 一致（当声明时）。
   * @throws IOException 当 resource 不可读、被删除或不属于该 store 时。
   */
  byte[] read(ResourceRef ref) throws IOException;
}

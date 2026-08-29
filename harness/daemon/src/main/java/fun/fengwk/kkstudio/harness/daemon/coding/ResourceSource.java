package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;

import java.io.IOException;

/** 读取本地已存储 resource 字节的 SPI，与 {@link ResourceSink} 配套使用。 */
public interface ResourceSource {

  /** 读取指定 ref 的完整字节。 */
  byte[] read(DaemonResourceRef ref) throws IOException;
}

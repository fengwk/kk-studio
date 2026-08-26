package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.tool.ResourceRef;

import java.io.IOException;

/** 将有界预览之外的完整 Environment Capability 输出存储为不可变 resources。 */
public interface ResourceSink {

  /** 持久化字节并返回其稳定的 canonical 引用。 */
  ResourceRef store(byte[] bytes, String mediaType) throws IOException;
}

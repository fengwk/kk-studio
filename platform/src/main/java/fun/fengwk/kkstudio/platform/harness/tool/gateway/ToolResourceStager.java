package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

/** 在 Harness 锁外把已验证的工具资源准备为可消费引用。 */
public interface ToolResourceStager {

  ResourceRef stage(String mediaType, String name, byte[] content);

  /** 放弃本次终态化已准备但尚未进入 durable history 的引用。 */
  void discard(ResourceRef resource);
}

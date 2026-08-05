/**
 * 宿主资源存储能力（{@link ResourceStore}）。
 *
 * <p>资源是内容寻址的字节对象：写入返回规范 file ResourceRef，读取只接受本能力拥有的规范引用。本包只定义窄端口，具体实现位于 runtime-spring 的 resource
 * adapter。该能力属于基础设施能力，不参与 Agent Loop 正确性判定；实现不得泄漏存储技术类型。
 */
package fun.fengwk.kkstudio.harness.runtime.resource;

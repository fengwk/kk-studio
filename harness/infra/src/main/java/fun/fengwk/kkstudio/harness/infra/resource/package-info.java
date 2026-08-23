/**
 * 本地文件 {@link ResourceStore} 适配：根目录固定的内容寻址对象存储。
 *
 * <p>只实现 {@link fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore} 窄端口，对象名一律由内容 SHA-256
 * 派生，存储路径从不来自资源 name 或输入 URI；读写失败向上传播为 {@link IllegalStateException}，绝不吞掉损坏。
 */
package fun.fengwk.kkstudio.harness.infra.resource;

import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;

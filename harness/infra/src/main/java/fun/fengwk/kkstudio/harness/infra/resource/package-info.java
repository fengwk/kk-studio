/**
 * 本地文件 {@link ResourceStore} 适配：根目录固定的内容寻址对象存储。
 *
 * <p>只实现 {@link fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore} 窄端口。对象名一律由内容 SHA-256
 * 十六进制编码派生，存储路径绝不来自资源 name 或外部输入 URI。写路径通过临时文件落盘并原子创建硬链接（create-only）发布，
 * 绝不覆盖已有对象；读取严格校验真实根目录、非符号链接并执行精确 size 与摘要校验；读写失败向上传播为 {@link IllegalStateException}，绝不吞掉损坏。
 */
package fun.fengwk.kkstudio.harness.infra.resource;

import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;

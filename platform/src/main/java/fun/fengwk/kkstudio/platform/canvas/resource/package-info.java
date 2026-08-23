/**
 * Canvas 全局媒体集成：blob 媒体事实 ffprobe 探针、webp 预览生成与输出物化。
 *
 * <p>浏览器直传与全局 blob 生命周期由 storage 基础负责；本包只做媒体增强（probe/preview）与 Canvas Resource 到 blob
 * 的绑定（物化），所有确定性对象键来自 {@code StorageObjectKeys}。
 */
package fun.fengwk.kkstudio.platform.canvas.resource;

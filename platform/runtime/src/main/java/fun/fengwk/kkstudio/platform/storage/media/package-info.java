/**
 * 全局 blob 的媒体增强：ffprobe 媒体事实探针与 webp 预览生成。
 *
 * <p>浏览器直传与 blob 生命周期由 storage 基础负责；本包只在上传确认后补充媒体事实与预览，所有确定性对象键来自 {@code StorageObjectKeys}。探针失败时退回
 * HEAD 元数据，非媒体内容的上传契约不受影响。
 */
package fun.fengwk.kkstudio.platform.storage.media;

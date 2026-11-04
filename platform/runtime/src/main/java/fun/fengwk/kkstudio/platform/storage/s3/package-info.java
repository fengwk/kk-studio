/**
 * S3 对象键等底层细节。
 *
 * <p>跨模块公开的 {@code S3StorageService}、{@code S3PresignService}、{@code S3ReadinessProbe} 及其返回类型仍在
 * {@code storage} 根包，以保持 canvas/web 调用方的方法签名稳定；只有不进入跨模块契约的 S3 细节归入本子包。
 */
package fun.fengwk.kkstudio.platform.storage.s3;

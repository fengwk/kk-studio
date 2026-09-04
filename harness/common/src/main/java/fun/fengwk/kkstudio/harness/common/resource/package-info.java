/**
 * 规范 Resource URI 引用及其格式校验器。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.common.resource.ResourceRef} 提供不可变规范 Resource URI 引用， 仅允许
 * {@code data}、{@code file}、{@code s3}、{@code https}、{@code http} 五类 scheme， 并在构造时由 {@link
 * fun.fengwk.kkstudio.harness.common.resource.ResourceUriValidator} 校验 URI 规范性、 UTF-8 字节上限、data
 * 解码载荷上限及 Unicode 代理项。
 *
 * <p>本包仅维护引用契约与格式校验，不负责资源的物理存取、网络传输或外部化持久化。
 */
package fun.fengwk.kkstudio.harness.common.resource;

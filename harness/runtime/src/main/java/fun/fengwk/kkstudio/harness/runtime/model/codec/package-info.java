/**
 * 共享的 model value-object JSON 编解码。
 *
 * <p>{@link ModelDescriptorJsonCodec} 是 {@code ModelDescriptor} 与 {@code ModelVariant} 的唯一权威 codec；
 * {@code ProviderRequestJsonCodec}（同模块 provider 包）必须把 model 与 variant 子树委派给它，避免两套字段实现导致 wire 漂移。
 *
 * <p>codec 边界拒绝：未知字段、缺失字段、错误类型、trailing token、duplicate field；{@code BigDecimal} 以 {@code
 * toPlainString} 字符串输出；{@code Set<Enum>} 字段按枚举名排序输出，确保跨 JVM deterministic。{@code
 * ModelDescriptor.providerName} / {@code modelName} are catalog name references; no database
 * resource IDs or secrets enter this boundary.
 */
package fun.fengwk.kkstudio.harness.runtime.model.codec;

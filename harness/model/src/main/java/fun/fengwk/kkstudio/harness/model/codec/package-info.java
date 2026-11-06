/**
 * 共享的 model value-object JSON 编解码。
 *
 * <p>{@link ModelDescriptorJsonCodec} 是 {@code ModelDescriptor} 与 {@code ModelVariant} 的唯一权威 codec。
 *
 * <p>codec 边界拒绝：未知字段、缺失字段、错误类型、trailing token、duplicate field；{@code BigDecimal} 以 {@code
 * toPlainString} 字符串输出；{@code Set<Enum>} 字段按枚举名排序输出，确保跨 JVM deterministic。{@code
 * ModelDescriptor.providerName} / {@code modelName} 是 catalog name 引用；本边界不允许数据库 resource ID 或密钥进入。
 */
package fun.fengwk.kkstudio.harness.model.codec;

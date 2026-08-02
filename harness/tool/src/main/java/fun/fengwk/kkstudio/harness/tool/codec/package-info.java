/**
 * 工具描述与 schema 的严格、确定性 JSON 编解码。
 *
 * <p>{@link ToolDescriptorJsonCodec} 同时支持 PLATFORM 与 ENVIRONMENT 工具，可与 Provider tool input-schema
 * JSON 共享使用。它在边界拒绝：
 *
 * <ul>
 *   <li>未知顶层字段、未知 schema 字段、未知 schema {@code type}；
 *   <li>trailing token、duplicate field（由共享 Jackson {@code ObjectMapper} 的 {@code
 *       FAIL_ON_TRAILING_TOKENS} 与 {@code STRICT_DUPLICATE_DETECTION} 强制）；
 *   <li>错误类型（如非 boolean 的 {@code additionalProperties}、非 string 的 enum 元素）；
 *   <li>object schema 缺失 {@code type=object} / {@code properties} / {@code required} / {@code
 *       additionalProperties}，以及 required 名称不在 properties 中或重复出现。
 * </ul>
 *
 * <p>object {@code properties} 按字典序排序，{@code required} 数组按字典序排序；enum 值保留输入顺序。
 *
 * <p>descriptor list 外部顺序不属于本 codec 职责——调用方需要时自行 canonical 排序。
 */
package fun.fengwk.kkstudio.harness.tool.codec;

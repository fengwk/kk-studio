/**
 * Session Entry {@code RUNTIME_CONFIG} 的最终不可变快照族与严格 deterministic JSON codec。
 *
 * <p>{@link RuntimeConfigSnapshot} 实现 {@link
 * fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload} 且 {@link
 * fun.fengwk.kkstudio.harness.runtime.entry.EntryType#RUNTIME_CONFIG type} 自报。聚合冻结 {@link
 * AgentSnapshot} / {@link ModelSnapshot} / 工具绑定 / {@link SkillSnapshot} / {@link
 * ExecutionPolicySnapshot} / {@link EnvironmentSnapshot}；credential reference 走 {@link
 * fun.fengwk.kkstudio.harness.model.ModelDescriptor#providerResourceId}。
 *
 * <p>{@link RuntimeConfigSource} 是 command-time live resource 冻结 SPI（agent 解析与 model 替换）；纯 YOLO 替换由
 * {@link RuntimeConfigSnapshot#withYoloEnabled(boolean)} 承担。
 *
 * <p>{@link RuntimeConfigJsonCodec} 是该快照族的唯一权威 JSON 编解码；逐字段 JsonNode 读写，拒绝未知 / 缺失 / 错误类型 / trailing
 * token / duplicate field / 显式 JSON null，固定字段顺序，集合按稳定 key canonical 排序。 {@link
 * fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec} 与 {@link
 * fun.fengwk.kkstudio.harness.model.codec.ModelDescriptorJsonCodec} 提供 node-level API，本 codec 直接复用。
 */
package fun.fengwk.kkstudio.harness.runtime.configuration;

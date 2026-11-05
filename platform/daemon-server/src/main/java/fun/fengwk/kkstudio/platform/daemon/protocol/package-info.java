/**
 * 控制协议的 protobuf binary 编解码、领域映射与应用层语义校验。
 *
 * <p>唯一的协议事实源是 {@code src/main/protocol/control.proto}；Java 在构建期用官方 protoc 生成，TypeScript 用
 * protoc-gen-es 生成并提交。
 *
 * <p>{@link ControlProtocolCodec} 负责成帧、protobuf 解析/序列化与领域映射； {@link ControlProtocolValidator}
 * 负责必填、范围、上限、UUID 等一次性语义校验。本包覆盖控制消息与 426 {@code UpgradeRequired} 载荷。
 */
package fun.fengwk.kkstudio.platform.daemon.protocol;

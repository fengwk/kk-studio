/**
 * 控制协议的 protobuf binary 编解码与应用层校验。
 *
 * <p>唯一的协议事实源是 {@code src/main/protocol/control.proto}；Java 在构建期用官方 protoc 生成，TypeScript 用
 * protoc-gen-es 生成并提交。本包覆盖控制消息与 426 {@code UpgradeRequired} 载荷。
 */
package fun.fengwk.kkstudio.harness.environment.protocol;

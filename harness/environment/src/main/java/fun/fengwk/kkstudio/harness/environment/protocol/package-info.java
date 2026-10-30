/**
 * v8 控制协议的 protobuf binary 编解码。
 *
 * <p>唯一的协议事实源是 {@code src/main/protocol/control.proto}；Java 在构建期用官方 protoc 生成，TypeScript 用
 * protoc-gen-es 生成并提交。本包覆盖 PLAN 3.4 的控制消息与 426 {@code UpgradeRequired} 载荷，尚未接入 v7 运行路径。 {@code
 * AGENT_COMMAND}/{@code AGENT_RESULT} 只预留字段号。
 */
package fun.fengwk.kkstudio.harness.environment.protocol;

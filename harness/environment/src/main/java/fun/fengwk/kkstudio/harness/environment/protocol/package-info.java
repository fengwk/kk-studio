/**
 * v8 控制协议的 protobuf binary 编解码检查点。
 *
 * <p>唯一的协议事实源是 {@code src/main/protocol/control.proto}；Java 在构建期用官方 protoc 生成，TypeScript 用
 * protoc-gen-es 生成并提交。当前只覆盖 INVOKE、COMPLETED、EXECUTION_EXITED，尚未接入任何 v7 运行路径。
 */
package fun.fengwk.kkstudio.harness.environment.protocol;

/**
 * Thread 级控制请求的公开值类型：停止、YOLO 开关与手动压缩。
 *
 * <p>三者就是 HarnessRuntime 对应控制方法的入参形状，只携带目标 Thread、客户端幂等键、expected version 与开关取值。
 *
 * <p>不引用 Store、Driver、Thread 状态等内部类型；必填字段与非负 version 在构造期校验。
 */
package fun.fengwk.kkstudio.harness.api.thread;

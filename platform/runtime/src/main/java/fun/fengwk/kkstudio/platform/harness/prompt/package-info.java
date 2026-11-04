/**
 * Prompt 任务与媒体窄端口的宿主实现：任务服务与存储桥接同包，媒体接收以包内方法完成。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.harness.prompt.PluginPromptTaskService} 负责 root
 * Session/Thread 创建、 TASK join 同事务接受与固定回执观察；{@link
 * fun.fengwk.kkstudio.platform.harness.prompt.PluginPromptResourceBridge}
 * 负责事务外暂存与事务内接收。两者共享同包可见的接收方法，因此 Harness/Storage 细节不会进入 plugin-api 的 SPI。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.harness.prompt.ChangeGate}
 * 是观察循环的信号门：按类别计数并提供限时等待切片，只负责唤醒，绝不读取 durable 事实。
 */
package fun.fengwk.kkstudio.platform.harness.prompt;

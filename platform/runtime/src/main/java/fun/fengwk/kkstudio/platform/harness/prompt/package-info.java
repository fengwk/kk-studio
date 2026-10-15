/**
 * Prompt 任务与媒体窄端口的宿主实现：任务服务与存储桥接同包，媒体接收以包内方法完成。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.harness.prompt.PluginPromptTaskService} 负责 root
 * Session/Thread 创建、 TASK join 同事务接受与固定回执观察；{@link
 * fun.fengwk.kkstudio.platform.harness.prompt.PluginPromptResourceBridge}
 * 负责事务外暂存与事务内接收。两者共享同包可见的接收方法，因此 Harness/Storage 细节不会进入 plugin-api 的 SPI。
 */
package fun.fengwk.kkstudio.platform.harness.prompt;

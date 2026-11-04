package fun.fengwk.kkstudio.harness.spi.driver;

/**
 * Driver 声明的能力集。
 *
 * @param fork 是否支持在该 Driver 的 Thread 上发起 fork（分支 fork 与会话 fork）
 * @param manualCompaction 是否支持用户发起的手工上下文压缩
 */
public record DriverCapabilities(boolean fork, boolean manualCompaction) {}

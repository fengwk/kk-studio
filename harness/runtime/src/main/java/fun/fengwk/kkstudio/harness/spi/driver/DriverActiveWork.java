package fun.fengwk.kkstudio.harness.spi.driver;

import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;

import java.util.UUID;

/** 活动 Driver Work 的归属和执行状态；running 不依赖 durable Work 是否仍有有效租约。 */
public record DriverActiveWork(UUID threadId, WorkTarget target, boolean running) {}

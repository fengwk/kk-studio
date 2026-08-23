package fun.fengwk.kkstudio.platform.studio.function;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;

/** start 短事务的幂等结果。 */
public record CanvasFunctionStartResult(CanvasFunctionRun run, boolean created) {}

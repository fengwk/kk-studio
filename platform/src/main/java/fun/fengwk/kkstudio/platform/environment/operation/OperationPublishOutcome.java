package fun.fengwk.kkstudio.platform.environment.operation;

/** 操作持久化发布终态枚举（包内私有）。 */
enum OperationPublishOutcome {
  APPLIED,
  RESOURCE_CHANGED,
  LEASE_LOST
}

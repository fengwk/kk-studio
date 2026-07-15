package fun.fengwk.kkstudio.harness.runtime.control;

/** 同一 kind 队列的冻结消费模式，在控制消息入库时定型为不可变快照。 */
public enum ControlConsumptionMode {
  /** 同 kind 同一时刻只有一条 PENDING；新消息入队时旧的同 kind PENDING 自动失效。 */
  ONE_AT_A_TIME,
  /** 同 kind 多条 PENDING 可共存，按 id asc 顺序消费直到清空。 */
  ALL
}
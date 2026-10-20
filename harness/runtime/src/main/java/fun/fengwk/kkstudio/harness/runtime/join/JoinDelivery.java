package fun.fengwk.kkstudio.harness.runtime.join;

/**
 * 票据（Join）结算后的交付方式；创建时确定、不可变。
 *
 * <p>三种方式共享同一份冻结结果：差别只在结算后谁被通知。
 */
public enum JoinDelivery {
  /** 向父 Thread 投递 {@code SUBAGENT_RESULT} 通知并标记交付。 */
  NOTIFY_PARENT,

  /** 只冻结结果并唤醒父 Thread：其 owner 的开放 Turn 自行消费，不产生通知。 */
  SETTLE_OWNER_TURN,

  /** 只冻结结果供调用方读取；无父 Thread，不通知任何人。 */
  RECEIPT_ONLY
}

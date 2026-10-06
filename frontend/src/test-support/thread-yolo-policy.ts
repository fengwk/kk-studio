import type { HarnessThreadYoloPolicyDTO } from '@/shared/api/contracts/ai-runtime'

/**
 * 测试基座：由产品级 boolean 派生根 Thread 的 YOLO 策略投影。
 * 根只可能是 ENABLE/DISABLE，rootThreadId 恒为 null；子代理的 FOLLOW 由测试显式构造。
 */
export function rootYoloPolicy(enabled: boolean): HarnessThreadYoloPolicyDTO {
  return { mode: enabled ? 'ENABLE' : 'DISABLE', rootThreadId: null }
}

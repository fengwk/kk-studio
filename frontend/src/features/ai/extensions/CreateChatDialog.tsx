import { lazy, Suspense } from 'react'
import { useOptionalChatRuntime } from '@/features/ai/chat/ChatRuntimeContext'

const CreateChatModal = lazy(async () => {
  const module = await import('@/features/ai/chat/CreateChatModal')
  return { default: module.CreateChatModal }
})

/** 只有 Chat Runtime 的创建弹窗实际打开时才加载表单与 Environment picker。 */
export function CreateChatDialog() {
  const controller = useOptionalChatRuntime()
  if (!controller?.createChatModal.open) {
    return null
  }
  return (
    <Suspense fallback={null}>
      <CreateChatModal {...controller.createChatModal} />
    </Suspense>
  )
}

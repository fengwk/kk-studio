import { useEffect, useRef, type KeyboardEvent } from 'react'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import type { AddMenuAction } from '@/features/canvas/types'
import { useI18n } from '@/shared/i18n'

const MENU_ITEMS: Array<{ action: AddMenuAction; label: string; icon: string; accept?: string }> = [
  { action: 'image-resource', label: '图片资源', icon: '▧', accept: 'image/jpeg,image/png,image/webp,image/heic,image/heif' },
  { action: 'video-resource', label: '视频资源', icon: '▶', accept: 'video/mp4,video/quicktime' },
  { action: 'audio-resource', label: '音频资源', icon: '♪', accept: 'audio/wav,audio/mpeg' },
  { action: 'text-resource', label: '文本资源', icon: 'T' },
  { action: 'image-function', label: '图片生成', icon: '✦' },
  { action: 'video-function', label: '视频生成', icon: '✧' },
  { action: 'group', label: '分组', icon: '□' },
]

/** 由 agent dock 组合的上弹式 add 菜单，支持键盘导航。 */
export function CanvasAddMenu({ menuId }: { menuId: string }) {
  const {
    state,
    closeAddMenu,
    setAddMenuIndex,
    handleAddAction,
    uploadFiles,
  } = useCanvasRuntime()
  const { t } = useI18n()

  const menuRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const menuButtons = MENU_ITEMS

  useEffect(() => {
    if (!state.addMenuOpen || !menuRef.current) {
      return
    }
    const action = menuButtons[state.addMenuIndex]?.action
    if (!action) {
      return
    }
    const button = menuRef.current.querySelector<HTMLButtonElement>(`[data-add-action="${action}"]`)
    button?.focus()
  }, [state.addMenuOpen, state.addMenuIndex, menuButtons])

  function handleMenuKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const count = menuButtons.length
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setAddMenuIndex((state.addMenuIndex + 1) % count)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setAddMenuIndex((state.addMenuIndex - 1 + count) % count)
    } else if (event.key === 'Home') {
      event.preventDefault()
      setAddMenuIndex(0)
    } else if (event.key === 'End') {
      event.preventDefault()
      setAddMenuIndex(count - 1)
    } else if (event.key === 'Escape') {
      event.preventDefault()
      closeAddMenu()
    } else if (event.key === 'Enter') {
      event.preventDefault()
      const item = menuButtons[state.addMenuIndex]
      if (item) {
        selectItem(item)
      }
    }
  }

  function selectItem(item: (typeof MENU_ITEMS)[number]) {
    if (item.accept) {
      if (fileInputRef.current) {
        fileInputRef.current.accept = item.accept
        fileInputRef.current.click()
      }
      return
    }
    handleAddAction(item.action)
  }

  return (
    <div
      className="add-menu"
      id={menuId}
      ref={menuRef}
      hidden={!state.addMenuOpen}
      inert={!state.addMenuOpen}
      aria-hidden={!state.addMenuOpen}
      role="menu"
      aria-label={t('canvas.add.ariaLabel')}
      onKeyDown={handleMenuKeyDown}
    >
      {menuButtons.map((item, index) => (
        <button
          key={item.action}
          type="button"
          role="menuitem"
          data-add-action={item.action}
          tabIndex={state.addMenuOpen && index === state.addMenuIndex ? 0 : -1}
          onClick={() => selectItem(item)}
          onMouseEnter={() => setAddMenuIndex(index)}
        >
          <span className="add-menu-icon">{item.icon}</span>
          <span>{item.label}</span>
        </button>
      ))}
      <input
        ref={fileInputRef}
        type="file"
        multiple
        hidden
        onChange={(event) => {
          if (event.target.files?.length) {
            void uploadFiles(event.target.files)
          }
          event.target.value = ''
          closeAddMenu()
        }}
      />
    </div>
  )
}

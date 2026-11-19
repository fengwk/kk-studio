import { useCallback, useEffect, useState, type KeyboardEvent, type RefObject } from 'react'
import {
  firstEnabledCommandIndex,
  stepEnabledCommandIndex,
  useFilteredThreadCommands,
} from '@/features/thread/runtime/thread-panel/thread-command-navigation'
import type { ThreadCommand } from '@/features/thread/runtime/thread-panel/thread-commands'
import type { ThreadComposerControlMenu } from '@/features/thread/runtime/thread-panel/ThreadComposerControls'
import type { ComposerPart } from '@/features/thread/composer/composer-parts'

/** 命令表的可见形态：menu 由 plus 按钮打开，slash 由草稿的 slash 查询打开。 */
type ComposerPaletteMode = 'menu' | 'slash' | null

interface ComposerPaletteOptions {
  active: boolean
  disabled: boolean
  isFocused: boolean
  /** plus 菜单是否打开（由覆盖层状态机持有）。 */
  plusMenuOpen: boolean
  setPlusMenuOpen: (open: boolean) => void
  /** 同步收起底栏设置菜单：两个覆盖层互斥，不依赖外部 mousedown。 */
  closeControlMenu: () => void
  /** 草稿的 slash 查询；null 表示没有处于 slash 模式。 */
  slashQuery: string | null
  commands: ThreadCommand[]
  parts: ComposerPart[]
  settings?: object
  fileInputRef: RefObject<HTMLInputElement | null>
  changeDraft: (parts: ComposerPart[]) => void
  onCommand: (command: ThreadCommand) => void
  onChangeControlMenu: (menu: ThreadComposerControlMenu) => void
  closeOverlay: () => boolean | void
  focusComposer: (forceCaretAtEnd?: boolean) => void
  blurComposer: () => void
  onSubmit: () => void
}

/**
 * 命令表：可见性、过滤结果、键盘游标，以及选中一条命令后的分派。
 *
 * 面板只在「激活、可编辑、区域有焦点」时展开；plus 菜单优先于 slash 查询。
 * 方向键在可用命令之间环绕，Enter 确认当前游标（落在禁用项时退到第一个可用项）。
 * upload 与 models 是 Composer 自身的动作（文件选择框、底栏菜单），其余交给宿主。
 */
export function useComposerPalette({
  active,
  disabled,
  isFocused,
  plusMenuOpen,
  setPlusMenuOpen,
  closeControlMenu,
  slashQuery,
  commands,
  parts,
  settings,
  fileInputRef,
  changeDraft,
  onCommand,
  onChangeControlMenu,
  closeOverlay,
  focusComposer,
  blurComposer,
  onSubmit,
}: ComposerPaletteOptions) {
  const slashMode = slashQuery != null
  const paletteMode: ComposerPaletteMode = !active || disabled || !isFocused
    ? null
    : plusMenuOpen
      ? 'menu'
      : slashMode
        ? 'slash'
        : null
  const paletteOpen = paletteMode != null
  const query = paletteMode === 'slash' ? slashQuery ?? '' : ''
  const filteredCommands = useFilteredThreadCommands(query, commands)
  // Host command arrays may be reconstructed every render. Reset only when the visible
  // palette/query changes, not when an equivalent array gets a new identity.
  const commandSignature = JSON.stringify(filteredCommands)

  const [activeIndex, setActiveIndex] = useState(0)
  const [keyboardScrollVersion, setKeyboardScrollVersion] = useState(0)

  useEffect(() => {
    if (!paletteOpen) {
      return
    }
    setActiveIndex(firstEnabledCommandIndex(filteredCommands))
  // `commandSignature` captures meaningful list changes without array identity churn.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paletteOpen, query, commandSignature])

  const closePalette = useCallback(() => {
    closeOverlay()
    focusComposer()
  }, [closeOverlay, focusComposer])

  /** plus 按钮：未展开时先收起设置菜单再打开命令表，展开时按当前形态关闭（slash 直接失焦）。 */
  const togglePalette = useCallback(() => {
    if (!paletteOpen) {
      closeControlMenu()
      setPlusMenuOpen(true)
      focusComposer()
      return
    }
    if (slashMode) {
      blurComposer()
      return
    }
    closePalette()
  }, [blurComposer, closeControlMenu, closePalette, focusComposer, paletteOpen, setPlusMenuOpen, slashMode])

  const selectCommand = useCallback((command: ThreadCommand) => {
    if (command.disabled) {
      return
    }
    const consumeSlashCommand = !plusMenuOpen && slashMode
    setPlusMenuOpen(false)
    if (consumeSlashCommand) {
      changeDraft(parts.filter((part) => part.type !== 'text'))
    }
    if (command.id === 'upload') {
      fileInputRef.current?.click()
      return
    }
    if (command.id === 'models') {
      if (settings != null) {
        onChangeControlMenu('model')
      }
      return
    }
    focusComposer()
    onCommand(command)
  }, [
    changeDraft,
    fileInputRef,
    focusComposer,
    onChangeControlMenu,
    onCommand,
    parts,
    plusMenuOpen,
    setPlusMenuOpen,
    settings,
    slashMode,
  ])

  /** Enter 确认当前游标命令；游标落在禁用项时退到第一个可用命令。 */
  const confirmActiveCommand = useCallback(() => {
    const command = filteredCommands[activeIndex]
    if (command && !command.disabled) {
      selectCommand(command)
      return
    }
    const fallback = filteredCommands.find((item) => !item.disabled)
    if (fallback) {
      selectCommand(fallback)
    }
  }, [activeIndex, filteredCommands, selectCommand])

  /** 编辑器未消费的按键：命令表导航优先，其次 Enter 提交。返回是否已消费。 */
  const handleEditorKeyDown = useCallback((event: KeyboardEvent<HTMLDivElement>): boolean => {
    if (paletteOpen && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault()
      event.stopPropagation()
      const delta = event.key === 'ArrowDown' ? 1 : -1
      setActiveIndex((current) => stepEnabledCommandIndex(filteredCommands, current, delta))
      setKeyboardScrollVersion((version) => version + 1)
      return true
    }
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      if (paletteOpen) {
        confirmActiveCommand()
      } else {
        onSubmit()
        focusComposer()
      }
      return true
    }
    return false
  }, [confirmActiveCommand, filteredCommands, focusComposer, onSubmit, paletteOpen])

  return {
    paletteOpen,
    query,
    filteredCommands,
    activeIndex,
    setActiveIndex,
    keyboardScrollVersion,
    togglePalette,
    closePalette,
    selectCommand,
    handleEditorKeyDown,
  }
}

import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { MediaLightbox } from '@/shared/ui/media/MediaLightbox'
import { Dialog } from '@/shared/ui/overlays/Dialog'

function LightboxHarness({ kind = 'image' as const, onClose }: { kind?: 'image' | 'video'; onClose: () => void }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button
        type="button"
        onClick={() => {
          setOpen(true)
        }}
      >
        打开预览
      </button>
      {open ? (
        <MediaLightbox
          kind={kind}
          label="photo.png"
          url="https://example.test/photo.png"
          ariaLabel="预览 photo.png"
          closeLabel="关闭预览"
          openLabel="打开原件 photo.png"
          onClose={() => {
            onClose()
            setOpen(false)
          }}
        />
      ) : null}
    </>
  )
}

describe('MediaLightbox', () => {
  it('portals the media into the shared modal layer and keeps the original-file entry', () => {
    render(
      <MediaLightbox
        kind="image"
        label="photo.png"
        url="https://example.test/photo.png"
        ariaLabel="预览 photo.png"
        closeLabel="关闭预览"
        openLabel="打开原件 photo.png"
        onClose={vi.fn()}
      />,
    )

    const dialog = screen.getByRole('dialog', { name: '预览 photo.png' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    // 复用共享弹图层级：卡片挂在 body 下的 .modal-backdrop 中。
    expect(dialog.closest('.modal-backdrop')?.parentElement).toBe(document.body)
    expect(screen.getByRole('button', { name: '关闭预览' })).toBeInTheDocument()

    const image = screen.getByRole('img', { name: 'photo.png' })
    expect(image).toHaveAttribute('src', 'https://example.test/photo.png')

    const originalLink = screen.getByRole('link', { name: '打开原件 photo.png' })
    expect(originalLink).toHaveAttribute('href', 'https://example.test/photo.png')
    expect(originalLink).toHaveAttribute('target', '_blank')
    expect(originalLink).toHaveAttribute('rel', 'noreferrer noopener')
  })

  it('renders videos with playback controls and no original-file link when it is not offered', () => {
    render(
      <MediaLightbox
        kind="video"
        label="clip.mp4"
        url="https://example.test/clip.mp4"
        ariaLabel="预览 clip.mp4"
        closeLabel="关闭预览"
        onClose={vi.fn()}
      />,
    )

    const video = document.querySelector('video') as HTMLVideoElement
    expect(video).toBeInTheDocument()
    expect(video).toHaveAttribute('src', 'https://example.test/clip.mp4')
    expect(video).toHaveAttribute('controls')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('closes on Escape and on backdrop press, returning focus to the opener', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<LightboxHarness onClose={onClose} />)

    const opener = screen.getByRole('button', { name: '打开预览' })
    await user.click(opener)
    expect(screen.getByRole('dialog', { name: '预览 photo.png' })).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    // 关闭后焦点归还给打开预览的按钮，而不是留在已卸载的弹层内。
    await waitFor(() => expect(opener).toHaveFocus())

    await user.click(screen.getByRole('button', { name: '打开预览' }))
    await user.click(document.querySelector('.modal-backdrop') as HTMLElement)
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('closes only the top layer when it is stacked above another dialog', async () => {
    const user = userEvent.setup()
    const outerClose = vi.fn()
    const closePreview = vi.fn()

    function StackHarness() {
      const [previewOpen, setPreviewOpen] = useState(false)
      return (
        <Dialog title="会话详情" onClose={outerClose}>
          <button type="button" onClick={() => setPreviewOpen(true)}>
            查看图片
          </button>
          {previewOpen ? (
            <MediaLightbox
              kind="image"
              label="photo.png"
              url="https://example.test/photo.png"
              ariaLabel="预览 photo.png"
              closeLabel="关闭预览"
              onClose={() => {
                closePreview()
                setPreviewOpen(false)
              }}
            />
          ) : null}
        </Dialog>
      )
    }

    render(<StackHarness />)
    await user.click(screen.getByRole('button', { name: '查看图片' }))
    expect(screen.getAllByRole('dialog')).toHaveLength(2)

    await user.keyboard('{Escape}')
    // Esc 只关闭最上层 Lightbox，父 Dialog 保持打开。
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
    expect(closePreview).toHaveBeenCalledOnce()
    expect(outerClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog', { name: '会话详情' })).toBeInTheDocument()
  })
})

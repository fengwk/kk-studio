import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import {
  hasBlockingModal,
  isEditableKeyboardTarget,
  isInsideBlockingModal,
  shouldDeferToBlockingModal,
} from '@/shared/ui/blocking-overlay'

describe('keyboard priority guards', () => {
  it('detects Modal/alertdialog/lightbox blocking overlays', () => {
    render(<div className="modal-backdrop" data-testid="modal" />)
    expect(hasBlockingModal()).toBe(true)

    document.body.innerHTML = '<div role="alertdialog"></div>'
    expect(hasBlockingModal()).toBe(true)

    document.body.innerHTML = '<div aria-modal="true"></div>'
    expect(hasBlockingModal()).toBe(true)

    document.body.innerHTML = '<div class="resource-media-lightbox"></div>'
    expect(hasBlockingModal()).toBe(true)

    document.body.innerHTML = '<div class="plain-panel"></div>'
    expect(hasBlockingModal()).toBe(false)

    document.body.innerHTML = ''
  })

  it('keeps panel handlers inside the overlay allowed', () => {
    document.body.innerHTML = '<div class="modal-backdrop"><section id="inside"></section></div><section id="outside"></section>'
    const inside = document.getElementById('inside')!
    const outside = document.getElementById('outside')!
    expect(isInsideBlockingModal(inside)).toBe(true)
    expect(isInsideBlockingModal(outside)).toBe(false)
    expect(shouldDeferToBlockingModal(inside)).toBe(false)
    expect(shouldDeferToBlockingModal(outside)).toBe(true)
    expect(shouldDeferToBlockingModal(null)).toBe(true)
    document.body.innerHTML = ''
  })

  it('classifies editable keyboard targets without duplicating isTyping logic', () => {
    document.body.innerHTML = [
      '<input id="input" />',
      '<textarea id="textarea"></textarea>',
      '<select id="select"></select>',
      '<div contenteditable="true" id="editable"></div>',
      '<div id="plain" tabindex="0"></div>',
      '<button id="button" type="button">b</button>',
    ].join('')
    // jsdom 未实现 isContentEditable；按真实浏览器语义补上该属性。
    Object.defineProperty(document.getElementById('editable'), 'isContentEditable', {
      configurable: true,
      value: true,
    })
    expect(isEditableKeyboardTarget(document.getElementById('input'))).toBe(true)
    expect(isEditableKeyboardTarget(document.getElementById('textarea'))).toBe(true)
    expect(isEditableKeyboardTarget(document.getElementById('select'))).toBe(true)
    expect(isEditableKeyboardTarget(document.getElementById('editable'))).toBe(true)
    expect(isEditableKeyboardTarget(document.getElementById('plain'))).toBe(false)
    expect(isEditableKeyboardTarget(document.getElementById('button'))).toBe(false)
    expect(isEditableKeyboardTarget(null)).toBe(false)
    expect(isEditableKeyboardTarget('not an element' as unknown as EventTarget)).toBe(false)
    document.body.innerHTML = ''
  })
})

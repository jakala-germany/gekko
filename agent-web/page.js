// gekko web agent, the part that runs inside the page. The bridge (bridge.mjs) injects it into every frame of the
// browser context; it only reads the page and offers a few helpers, it never sends anything anywhere. The Kotlin CLI
// decides what an element is, what is on screen and when the screen is done.
(() => {
  'use strict'
  if (window.__gekko) return

  const refs = new Map()
  let nextRef = 1

  const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'HEAD', 'META', 'LINK', 'TITLE', 'BR', 'WBR', 'OPTION', 'OPTGROUP'])
  // Their content is not part of this tree: an iframe is read by the bridge, the others have no elements of interest
  const LEAF_TAGS = new Set(['SVG', 'IFRAME', 'FRAME', 'CANVAS', 'VIDEO', 'AUDIO', 'IMG', 'SELECT', 'TEXTAREA', 'OBJECT', 'EMBED'])
  const CLICKABLE_ROLES = new Set(['button', 'link', 'tab', 'menuitem', 'menuitemcheckbox', 'menuitemradio', 'checkbox', 'radio', 'switch', 'option', 'treeitem', 'combobox', 'gridcell'])
  const TEXT_INPUTS = new Set(['text', 'search', 'email', 'url', 'tel', 'password', 'number', 'date', 'time', 'datetime-local', 'month', 'week', 'color', 'range', ''])
  const BUTTON_INPUTS = new Set(['button', 'submit', 'reset', 'image'])
  const CHECK_ROLES = new Set(['checkbox', 'switch', 'radio', 'menuitemcheckbox', 'menuitemradio'])
  const IMPLICIT_ROLES = {
    BUTTON: 'button', A: 'link', SELECT: 'combobox', TEXTAREA: 'textbox', IMG: 'img', NAV: 'navigation', MAIN: 'main', HEADER: 'banner',
    FOOTER: 'contentinfo', ASIDE: 'complementary', UL: 'list', OL: 'list', LI: 'listitem', TABLE: 'table', TR: 'row', TD: 'cell', TH: 'columnheader',
    FORM: 'form', DIALOG: 'dialog', PROGRESS: 'progressbar', METER: 'meter', H1: 'heading', H2: 'heading', H3: 'heading', H4: 'heading',
    H5: 'heading', H6: 'heading', SUMMARY: 'button', LABEL: 'label', SVG: 'img', CANVAS: 'canvas', VIDEO: 'video', AUDIO: 'audio',
  }

  const clean = (text) => (text || '').replace(/\s+/g, ' ').trim()

  const roleOf = (el) => {
    const explicit = el.getAttribute('role')
    if (explicit) return explicit.split(/\s+/)[0]
    if (el.tagName === 'INPUT') {
      const type = (el.getAttribute('type') || 'text').toLowerCase()
      if (type === 'checkbox' || type === 'radio') return type
      if (BUTTON_INPUTS.has(type)) return 'button'
      if (type === 'range') return 'slider'
      return 'textbox'
    }
    return IMPLICIT_ROLES[el.tagName] || null
  }

  const inputType = (el) => (el.tagName === 'INPUT' ? (el.getAttribute('type') || 'text').toLowerCase() : null)

  const isEditable = (el) => {
    if (el.tagName === 'TEXTAREA') return !el.readOnly
    if (el.tagName === 'SELECT') return true
    if (el.tagName === 'INPUT') return TEXT_INPUTS.has(inputType(el)) && !el.readOnly
    const flag = el.getAttribute('contenteditable')
    return flag !== null && flag.toLowerCase() !== 'false'
  }

  const textOf = (el, role) => {
    const tag = el.tagName
    if (tag === 'INPUT') {
      const type = inputType(el)
      if (type === 'password') return null
      if (BUTTON_INPUTS.has(type)) return clean(el.value || (type === 'submit' ? 'Submit' : type === 'reset' ? 'Reset' : ''))
      if (type === 'checkbox' || type === 'radio') return ''
      return el.value || el.getAttribute('placeholder') || ''
    }
    if (tag === 'TEXTAREA') return el.value || el.getAttribute('placeholder') || ''
    if (tag === 'SELECT') return clean(Array.from(el.selectedOptions).map((option) => option.text).join(', '))
    let own = ''
    for (const child of el.childNodes) if (child.nodeType === 3) own += child.nodeValue
    own = clean(own)
    if (own) return own
    // A button, link or tab shows the text of what is inside it, like a merged accessibility node
    if (isInteractiveByTag(el) || CLICKABLE_ROLES.has(role)) {
      const inner = clean(el.innerText)
      if (inner) return inner
    }
    return ''
  }

  const isInteractiveByTag = (el) => {
    const tag = el.tagName
    return tag === 'BUTTON' || tag === 'A' || tag === 'SUMMARY' || tag === 'LABEL'
  }

  const descriptionOf = (el) => {
    let description = el.getAttribute('aria-label')
    if (!description) {
      const ids = el.getAttribute('aria-labelledby')
      if (ids) {
        description = clean(
          ids
            .split(/\s+/)
            .map((id) => (el.getRootNode().getElementById ? el.getRootNode().getElementById(id) : null))
            .map((label) => (label ? label.textContent : ''))
            .join(' ')
        )
      }
    }
    if (!description && el.labels && el.labels.length > 0) description = clean(el.labels[0].innerText || el.labels[0].textContent)
    if (!description) description = el.getAttribute('title')
    if (!description && el.tagName === 'IMG') description = el.getAttribute('alt')
    return description ? clean(description) : null
  }

  const idOf = (el, idAttributes) => {
    for (const name of idAttributes) {
      const value = el.getAttribute(name)
      if (value) return value
    }
    return null
  }

  const stateChecked = (el, role) => {
    if (el.tagName === 'INPUT' && (inputType(el) === 'checkbox' || inputType(el) === 'radio')) return !!el.checked
    if (CHECK_ROLES.has(role)) return el.getAttribute('aria-checked') === 'true'
    const pressed = el.getAttribute('aria-pressed')
    if (pressed === 'true' || pressed === 'false') return pressed === 'true'
    return null
  }

  const stateSelected = (el) => {
    const selected = el.getAttribute('aria-selected')
    if (selected === 'true' || selected === 'false') return selected === 'true'
    return null
  }

  const isEnabled = (el) => {
    if (el.getAttribute('aria-disabled') === 'true') return false
    try {
      return !el.matches(':disabled')
    } catch (error) {
      return true
    }
  }

  const isBusy = (el, role) => {
    if (el.getAttribute('aria-busy') === 'true') return true
    if (el.tagName === 'PROGRESS' && !el.hasAttribute('value')) return true
    return role === 'progressbar' && !el.hasAttribute('aria-valuenow') && !el.hasAttribute('value')
  }

  const flatChildren = (el) => {
    if (el.shadowRoot) return Array.from(el.shadowRoot.children)
    if (el.tagName === 'SLOT') {
      const assigned = el.assignedElements({ flatten: true })
      return assigned.length > 0 ? assigned : Array.from(el.children)
    }
    return Array.from(el.children)
  }

  const intersect = (a, b) => {
    if (!a) return b
    if (!b) return a
    const left = Math.max(a[0], b[0])
    const top = Math.max(a[1], b[1])
    const right = Math.min(a[2], b[2])
    const bottom = Math.min(a[3], b[3])
    return right > left && bottom > top ? [left, top, right, bottom] : [0, 0, 0, 0]
  }

  const round = (rect) => ({ left: Math.round(rect.left), top: Math.round(rect.top), right: Math.round(rect.right), bottom: Math.round(rect.bottom) })

  const paddingBox = (el, rect) => {
    const sx = el.offsetWidth ? rect.width / el.offsetWidth : 1
    const sy = el.offsetHeight ? rect.height / el.offsetHeight : 1
    const left = rect.left + el.clientLeft * sx
    const top = rect.top + el.clientTop * sy
    return [left, top, left + el.clientWidth * sx, top + el.clientHeight * sy]
  }

  // An element that an absolutely positioned descendant is placed (and clipped) by
  const isContainingBlock = (style) =>
    style.display !== 'contents' &&
    (style.position !== 'static' || style.transform !== 'none' || style.perspective !== 'none' || style.filter !== 'none' || /paint|layout|strict|content/.test(style.contain))

  const snapshot = (idAttributes) => {
    refs.clear()
    nextRef = 1
    const viewport = [0, 0, window.innerWidth, window.innerHeight]
    const modalElement = findModal()
    let modalNodes = null

    // [blockClip] is what the nearest containing block cuts off, handed down the walk instead of looked up per element
    const walk = (el, clip, parentCursor, blockClip) => {
      if (SKIP_TAGS.has(el.tagName)) return []
      const style = getComputedStyle(el)
      if (style.display === 'none') return []
      const rect = el.getBoundingClientRect()

      let own = clip
      if (style.position === 'fixed') own = viewport
      else if (style.position === 'absolute') own = blockClip
      let forChildren = own
      const clips = (style.overflowX !== 'visible' || style.overflowY !== 'visible') && style.display !== 'inline' && style.display !== 'contents'
      if (clips && el !== document.documentElement && el !== document.body) forChildren = intersect(own, paddingBox(el, rect))
      const blockForChildren = isContainingBlock(style) ? forChildren : blockClip

      const children = []
      if (!LEAF_TAGS.has(el.tagName)) {
        for (const child of flatChildren(el)) children.push(...walk(child, forChildren, style.cursor, blockForChildren))
      }

      const role = roleOf(el)
      const visible = style.visibility !== 'hidden' && style.visibility !== 'collapse' && style.display !== 'contents'
      const hasArea = rect.width > 0 && rect.height > 0
      let nodes = children
      if (visible && hasArea) {
        const id = idOf(el, idAttributes)
        const secure = inputType(el) === 'password'
        const text = textOf(el, role)
        const description = descriptionOf(el)
        const actions = []
        const pointerRoot = style.cursor === 'pointer' && parentCursor !== 'pointer'
        const clickable =
          isInteractiveByTag(el) || el.tagName === 'SELECT' || (el.tagName === 'INPUT' && inputType(el) !== 'hidden') ||
          CLICKABLE_ROLES.has(role) || el.hasAttribute('onclick') || pointerRoot
        if (clickable) actions.push('click')
        if (isEditable(el)) actions.push('setText')
        const scrollsY = /(auto|scroll|overlay)/.test(style.overflowY) && el.scrollHeight > el.clientHeight + 1
        const scrollsX = /(auto|scroll|overlay)/.test(style.overflowX) && el.scrollWidth > el.clientWidth + 1
        if ((scrollsY || scrollsX) && el !== document.documentElement && el !== document.body) actions.push('scroll')
        const busy = isBusy(el, role)
        if (id || text || description || actions.length > 0 || busy || el.hasAttribute('role')) {
          const ref = nextRef++
          refs.set(ref, new WeakRef(el))
          const node = {
            ref: String(ref),
            id,
            text: secure ? null : text || null,
            description: description && description !== text ? description : null,
            role,
            bounds: round(rect),
            actions,
            enabled: isEnabled(el),
            checked: stateChecked(el, role),
            selected: stateSelected(el),
            secure,
            busy,
            children,
          }
          if (own !== viewport && (own[0] > 0 || own[1] > 0 || own[2] < viewport[2] || own[3] < viewport[3])) {
            node.clip = { left: Math.round(own[0]), top: Math.round(own[1]), right: Math.round(own[2]), bottom: Math.round(own[3]) }
          }
          nodes = [node]
        }
      }
      if (el === modalElement) modalNodes = nodes
      return nodes
    }

    const root = document.documentElement
    const rootNodes = root ? walk(root, viewport, 'auto', viewport) : []
    const scroller = document.scrollingElement
    const pageScrolls = !!scroller && scroller.scrollHeight > scroller.clientHeight + 1 && getComputedStyle(root).overflowY !== 'hidden'
    const loading = document.readyState !== 'complete'
    const wrapper = (children) => ({
      ref: '0',
      role: 'document',
      bounds: { left: 0, top: 0, right: viewport[2], bottom: viewport[3] },
      actions: pageScrolls ? ['scroll'] : [],
      enabled: true,
      secure: false,
      busy: loading,
      children,
    })
    return {
      windows: [wrapper(modalNodes || rootNodes)],
      modal: !!modalNodes,
      viewport: { w: viewport[2], h: viewport[3] },
      rtl: !!root && getComputedStyle(root).direction === 'rtl',
      url: location.href,
      title: document.title,
    }
  }

  // The topmost modal dialog hides everything below it, like a dialog window on Android
  function findModal() {
    const candidates = []
    for (const dialog of document.querySelectorAll('dialog[open]')) {
      try {
        if (dialog.matches(':modal')) candidates.push(dialog)
      } catch (error) {
        // :modal is not known to this browser
      }
    }
    for (const element of document.querySelectorAll('[aria-modal="true"]')) {
      const rect = element.getBoundingClientRect()
      if (rect.width > 0 && rect.height > 0 && getComputedStyle(element).visibility !== 'hidden') candidates.push(element)
    }
    return candidates.length > 0 ? candidates[candidates.length - 1] : null
  }

  const element = (ref) => {
    const found = refs.get(Number(ref))
    return found ? found.deref() || null : null
  }

  // What gets the text of a type command: the element itself or the first editable element inside it
  const editable = (ref) => {
    const target = element(ref)
    if (!target) return null
    if (isEditable(target)) return target
    return target.querySelector('input:not([type=hidden]), textarea, select, [contenteditable]:not([contenteditable=false])')
  }

  const blur = () => {
    const active = document.activeElement
    if (active && active !== document.body && typeof active.blur === 'function') active.blur()
    return true
  }

  // Brings an element of the page into the window, like a person scrolling the page to it
  const reveal = (ref) => {
    const target = element(ref)
    if (!target) return false
    target.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' })
    return true
  }

  window.__gekko = { snapshot, element, editable, blur, reveal }
})()

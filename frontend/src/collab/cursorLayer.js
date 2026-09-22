import { Plugin, PluginKey } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'

export const cursorKey = new PluginKey('remote-cursors')

const renderWidget = (peer) => {
  const dom = document.createElement('span')
  dom.className = 'remote-caret'
  dom.style.borderLeft = `2px solid ${peer.color}`
  dom.style.marginLeft = '-1px'
  dom.style.height = '1.1em'
  dom.style.verticalAlign = 'text-bottom'
  dom.style.display = 'inline-block'

  const label = document.createElement('span')
  label.className = 'remote-caret-label'
  label.style.backgroundColor = peer.color
  label.textContent = peer.username
  dom.appendChild(label)
  return dom
}

const buildSet = (doc, peers) => {
  const decorations = []
  for (const peer of peers) {
    if (typeof peer.position !== 'number') continue
    const pos = Math.min(Math.max(peer.position, 1), Math.max(doc.content.size - 1, 1))
    let $pos
    try {
      $pos = doc.resolve(pos)
    } catch {
      continue
    }
    if (!$pos.parent.isTextblock) continue
    decorations.push(Decoration.widget(pos, () => renderWidget(peer), {
      key: `remote-caret-${peer.userId}`
    }))
  }
  decorations.sort((a, b) => a.from - b.from)
  return DecorationSet.create(doc, decorations)
}

/**
 * Draws each collaborator's caret as an inline widget.
 *
 * Positions are absolute document offsets from a CURSOR_UPDATE, so they are mapped through every local
 * transaction to stay where they belong as the document changes; they are only rebuilt from the peer
 * list when a peer joins, leaves or moves. A caret can therefore sit slightly behind its owner between
 * their cursor events, which is the accepted trade for not shipping a relative-position protocol.
 */
export const createCursorLayer = (getPeers) => new Plugin({
  key: cursorKey,
  state: {
    init: (_, state) => buildSet(state.doc, getPeers()),
    apply: (tr, previous, _old, state) =>
      (tr.getMeta(cursorKey) === undefined
        ? previous.map(tr.mapping, state.doc)
        : buildSet(state.doc, getPeers()))
  },
  props: {
    decorations(state) {
      return this.getState(state)
    }
  }
})

/** Tells the layer to re-read the peer list; call after adding or removing a collaborator. */
export const refreshCursors = (view) => view.dispatch(view.state.tr.setMeta(cursorKey, {}))

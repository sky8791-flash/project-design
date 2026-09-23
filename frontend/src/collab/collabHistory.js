import { Plugin } from '@tiptap/pm/state'

/**
 * The one thing this plugin does is exist with `historyPreserveItems` set.
 *
 * `prosemirror-history` collapses the items between undoable events unless some plugin in the state asks it to
 * preserve them, and it can only rebase its stored steps if each of them is still individually addressable:
 * `Branch.rebased` walks the rebase transform's mirrors to find the inverse of each of our unacknowledged
 * steps, and that walk is meaningless once those items have been merged into one. `otClient` sends the
 * matching `rebased` meta on every transaction that lifts and replays local work; without this declaration the
 * history ignores it, and an undo after a peer edit can re-apply an inverse computed against a document that no
 * longer exists.
 */
export const createHistoryFlags = () => new Plugin({ historyPreserveItems: true })

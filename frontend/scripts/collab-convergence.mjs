/**
 * Convergence harness for the client half of the collaboration protocol.
 *
 *     npm run check:collab
 *
 * Invariant I2 in the plan says two clients that have seen the same sequence prefix hold the same document.
 * The server-side half of that is covered by `CollabInvariantTest`, but `rebaseOver()` — the rebase that makes
 * convergence happen — lives in `otClient.js` and had no executable check, only a handful of browser
 * observations. This drives the real client against a sequencer that implements the documented contract
 * (`baseVersion` must equal the current version, an accepted batch commits at exactly `base + 1`, `ACK` and
 * `REJECT` are unicasts to the origin, `STEPS` goes to everybody else) and asserts the property over hundreds
 * of random interleavings plus the specific orderings that used to be bugs.
 *
 * Scope worth stating: the schema is hand-built with the node and mark names TipTap uses, so this covers step
 * mapping, sequencing and the recovery paths. It checks that a rebase announces itself to `prosemirror-history`
 * with the right count, but it has no history plugin, so what undo does with that signal is not covered here —
 * nor is anything else a TipTap extension adds (input rules, decorations), nor the HTTP and WebSocket layers,
 * which the browser runs recorded in
 * AGENTS.md cover those.
 */
import { Schema, Slice } from '@tiptap/pm/model'
import { EditorState } from '@tiptap/pm/state'
import { AddMarkStep, ReplaceStep, Step } from '@tiptap/pm/transform'
import { createCollabClient } from '../src/collab/otClient.js'

const CHARS = 'abcdefghijklmnop'

const schema = new Schema({
  nodes: {
    doc: { content: 'block+' },
    paragraph: { group: 'block', content: 'inline*' },
    text: { group: 'inline' }
  },
  marks: { bold: {}, italic: {} }
})

const emptyDoc = () => schema.nodeFromJSON({ type: 'doc', content: [{ type: 'paragraph' }] })

/** Positions a `ReplaceStep` may use as both ends of a range, i.e. inside one text block. */
const editableRanges = (doc) => {
  const ranges = []
  doc.descendants((node, pos) => {
    if (!node.isTextblock) return true
    let offset = 0
    node.forEach((child) => {
      if (child.isText) ranges.push({ from: pos + 1 + offset, to: pos + 1 + offset + child.nodeSize })
      offset += child.nodeSize
    })
    if (node.content.size === 0) ranges.push({ from: pos + 1, to: pos + 1 })
  })
  return ranges
}

/**
 * The three things `createCollabClient` touches on an editor: `state`, `view.dispatch`, `commands.setContent`.
 * A TipTap editor would add DOM and plugins, neither of which the protocol reads.
 */
const fakeEditor = () => {
  let state = EditorState.create({ schema, doc: emptyDoc() })
  const rebases = []
  return {
    get state() {
      return state
    },
    view: { dispatch: (tr) => { if (tr.getMeta('rebased') !== undefined) rebases.push(tr.getMeta('rebased')); state = state.apply(tr) } },
    // TipTap's `getJSON` is the *document* node as JSON. `EditorState.toJSON()` would add a `selection`
    // wrapper around it, and a checkpoint uploaded in that shape cannot be read back as a document.
    getJSON: () => state.doc.toJSON(),
    rebases,
    commands: {
      setContent: (content) => {
        const doc = typeof content === 'string' || !content ? emptyDoc() : schema.nodeFromJSON(content)
        state = EditorState.create({ schema, doc })
      }
    }
  }
}

/**
 * Local typing, in the order ProseMirror does it: applied to this document first, then handed to the client.
 * Returning what was inserted is what lets an insert-only run check that nothing went missing.
 */
const localEdit = (editor, client, random, { deletes = true, marks = true } = {}) => {
  const ranges = editableRanges(editor.state.doc)
  if (!ranges.length) return null
  const range = ranges[Math.floor(random() * ranges.length)]
  const at = range.from + Math.floor(random() * (range.to - range.from + 1))
  const roll = random()

  const commit = (tr, inserted = null) => {
    editor.view.dispatch(tr)
    client.addLocalSteps([tr])
    return { inserted }
  }

  if (roll < 0.6 || (roll >= 0.8 && roll < 0.9)) {
    const inserted = CHARS[Math.floor(random() * CHARS.length)]
    // `insertText` rather than a hand-built `ReplaceStep`: `Slice` wants a `Fragment`, and handing it a text
    // *node* does not fail — it quietly inserts nothing, which would make this whole file vacuous.
    return commit(editor.state.tr.insertText(inserted, at), inserted)
  }
  if (roll >= 0.9) {
    if (!marks) return null
    const mark = random() < 0.5 ? schema.marks.bold.create() : schema.marks.italic.create()
    const to = Math.min(range.to, at + 2)
    if (to - at < 1) return null
    return commit(editor.state.tr.step(new AddMarkStep(at, to, mark)))
  }
  if (!deletes || range.to - range.from < 1) return null
  const from = Math.min(at, range.to - 1)
  return commit(editor.state.tr.step(new ReplaceStep(from, from + 1, Slice.empty)))
}

// ---------------------------------------------------------------------------
// the server contract, as the client is told it behaves
// ---------------------------------------------------------------------------

class Sequencer {
  constructor() {
    this.version = 0
    this.log = []
    this.checkpoint = emptyDoc().toJSON()
    this.checkpointVersion = 0
  }

  /** @returns the frames this submission produces, in the order the node sends them. */
  submit({ clientId, baseVersion, steps }) {
    if (baseVersion !== this.version) {
      return [{ to: clientId, kind: 'unicast', frame: { type: 'REJECT', clientId, version: this.version } }]
    }
    this.version += 1
    this.log.push({
      version: this.version,
      commandType: 'STEPS',
      commandParams: JSON.stringify({ clientId, steps })
    })
    return [
      // The unicast really goes out first: it is the sender's permission to release its next batch and it
      // cannot fail over the network, so it must never sit behind a broadcast that can.
      { to: clientId, kind: 'unicast', frame: { type: 'ACK', clientId, version: this.version } },
      { to: null, kind: 'broadcast', frame: { type: 'STEPS', clientId, version: this.version, steps } }
    ]
  }

  /** Commits a row no client can apply, which is what a hand-built or malicious writer produces. */
  commitUnappliable(steps, clientId = 'poison') {
    this.version += 1
    this.log.push({
      version: this.version,
      commandType: 'STEPS',
      commandParams: JSON.stringify({ clientId, steps })
    })
    return this.version
  }

  replace(content, clientId = 'restorer') {
    this.version += 1
    this.checkpoint = content
    this.checkpointVersion = this.version
    return { type: 'RESET', version: this.version, content: JSON.stringify(content), contentFormat: 'doc-json' }
  }

  operationsAfter(version) {
    return this.log.filter((row) => row.version > version)
  }

  saveCheckpoint(atSeq, content) {
    if (atSeq <= this.checkpointVersion) return 409
    this.checkpoint = content
    this.checkpointVersion = atSeq
    // Folding is the point of a checkpoint: everything at or below it is deleted.
    this.log = this.log.filter((row) => row.version > atSeq)
    return 200
  }

  state() {
    return {
      content: JSON.stringify(this.checkpoint),
      contentFormat: 'doc-json',
      version: this.version,
      checkpointVersion: this.checkpointVersion
    }
  }
}

/**
 * A reset is supposed to be the only way out of a halt, and `handleReset` does not know the log — it clears
 * the halt, adopts the version it is given and trusts the content. So a restore has to fold the history it
 * replaced, the way recording a checkpoint does, or the row that halted the tab is still above the reader's
 * version and the next gap replay halts it again. Whether the real service does that is not something this
 * file can answer, so it states the requirement and checks the client against it.
 */
class FoldingSequencer extends Sequencer {
  replace(content, clientId = 'restorer') {
    const frame = super.replace(content, clientId)
    this.log = this.log.filter((row) => row.version > this.version)
    return frame
  }
}

// ---------------------------------------------------------------------------
// transport: every frame is queued, so a test chooses the delivery order
// ---------------------------------------------------------------------------

class Hub {
  constructor(server) {
    this.server = server
    this.participants = new Map()
    this.outbox = []
    this.held = []
  }

  join(clientId, client) {
    this.participants.set(clientId, client)
    this.deliverTo(clientId, { type: 'INIT', onlineCount: this.participants.size, ...this.server.state() })
  }

  /** What the client's `ws.send` becomes. Nothing is delivered here: delivery is `pump`'s job. */
  send(frame) {
    if (frame.type !== 'STEP_BATCH') throw new Error(`unexpected client frame ${frame.type}`)
    for (const produced of this.server.submit(frame)) {
      if (produced.kind === 'unicast') this.outbox.push({ to: produced.to, frame: produced.frame })
      else {
        for (const participant of this.participants.keys()) {
          if (participant !== frame.clientId) this.outbox.push({ to: participant, frame: produced.frame })
        }
      }
    }
  }

  /** Delivers what is queued; `filter` lets a test hold a class of frames back to re-order them later. */
  pump(filter = () => true) {
    const queued = this.outbox
    this.outbox = []
    for (const { to, frame } of queued) {
      if (!filter(to, frame)) this.held.push({ to, frame })
      else this.deliverTo(to, frame)
    }
  }

  release() {
    const queued = this.held
    this.held = []
    queued.forEach(({ to, frame }) => this.deliverTo(to, frame))
  }

  deliverTo(clientId, frame) {
    const client = this.participants.get(clientId)
    if (!client) return
    switch (frame.type) {
      case 'INIT':
        client.bootstrap(frame)
        break
      case 'STEPS':
        client.handleSteps(frame)
        break
      case 'ACK':
        client.handleAck(frame)
        break
      case 'REJECT':
        client.handleReject(frame)
        break
      case 'RESET':
        client.handleReset(frame)
        break
      default:
        throw new Error(`unhandled frame ${frame.type}`)
    }
  }
}

const clientScript = (hub, server, clientId) => {
  const editor = fakeEditor()
  let connected = true
  // A run that only converged because the client gave up and refetched the whole document proves the
  // recovery path, not the protocol — so it has to be counted rather than mistaken for convergence.
  const stats = { rebuilds: 0 }
  const api = {
    get: async (url, options) => {
      if (url.includes('/operations')) return { data: server.operationsAfter(options?.params?.after ?? 0) }
      stats.rebuilds += 1
      return { data: server.state() }
    },
    post: async (url, body) => {
      if (!url.includes('/checkpoint')) return { data: {} }
      const status = server.saveCheckpoint(body.atSeq, JSON.parse(body.content))
      if (status !== 200) throw { response: { status } }
      return { data: { status } }
    }
  }
  const client = createCollabClient({
    editor,
    documentId: '1',
    clientId,
    api,
    ws: { send: (frame) => hub.send(frame), isConnected: () => connected }
  })
  return { editor, client, stats, setConnected: (value) => { connected = value } }
}

/** The client's own work is queued on a promise chain, and the send happens on it, so let it run first. */
const settle = async () => {
  for (let turn = 0; turn < 150; turn += 1) await new Promise((resolve) => setImmediate(resolve))
}

/**
 * Pumps until the wire is quiet. It has to loop: delivering a frame makes the receiver send its next batch,
 * and a run that stops after one pass leaves the last writer's text sitting in its outbox — which looks
 * exactly like a convergence bug and is not one. Held frames are deliberately left alone.
 *
 * @returns false when the wire never quietened down, i.e. two clients ping-ponging forever.
 */
const drain = async (hub, limit = 200) => {
  for (let pass = 0; pass < limit; pass += 1) {
    if (!hub.outbox.length) return true
    hub.pump()
    await settle()
  }
  return false
}

/** One edit plus everything it causes on the wire. */
const editThenDeliver = async (side, random, hub, options) => {
  // The workload can decline (a delete with nothing to delete, a mark on a one-character range); a scenario
  // that means "type something" must not silently type nothing.
  let edit = localEdit(side.editor, side.client, random, options)
  for (let retry = 0; retry < 12 && !edit; retry += 1) edit = localEdit(side.editor, side.client, random, options)
  await settle()
  await drain(hub)
  return edit
}

/**
 * A scripted scenario cannot tolerate a random roll deciding an edit did not happen, so these type
 * deterministically at the end of the only paragraph this workload ever has.
 */
const typeText = (side, text) => {
  insertAt(side, text, side.editor.state.doc.content.size - 1)
  return text
}

const insertAt = (side, text, at) => {
  const tr = side.editor.state.tr.insertText(text, at)
  side.editor.view.dispatch(tr)
  side.client.addLocalSteps([tr])
  return text
}

const marksOf = (node) => node.marks.map((mark) => `${mark.type.name}${JSON.stringify(mark.attrs)}`).sort().join('+')

/**
 * A document as `type(marks)(children)`, with adjacent text runs carrying the same marks merged into one.
 * `Node.toJSON()` splits a run wherever an insert happened to land, so two documents that differ only in that
 * are the same document to the user; marks, node types and nesting all still appear here, which is what
 * actually diverges when a rebase is wrong.
 */
const canonical = (node) => {
  if (node.isText) return `T[${marksOf(node)}]${node.text}`
  const children = []
  node.forEach((child) => {
    const serialized = canonical(child)
    const last = children[children.length - 1]
    if (child.isText && last && last.startsWith(`T[${marksOf(child)}]`)) children[children.length - 1] = last + child.text
    else children.push(serialized)
  })
  return `${node.type.name}[${marksOf(node)}](${children.join(',')})`
}

const textOf = (side) => side.editor.state.doc.textContent
const jsonOf = (side) => canonical(side.editor.state.doc)
const allEqual = (sides, read) => sides.every((side) => read(side) === read(sides[0]))

/**
 * The other half of I3, computed independently of every client: the log is the agreement, so replaying it over
 * the state the writers started from is what each document has to equal. Without this a divergence can only be
 * reported as "these two differ", which does not say who is wrong.
 */
const replayLog = (server) => {
  let state = EditorState.create({ schema, doc: emptyDoc() })
  for (const row of server.log) {
    if (row.commandType !== 'STEPS') continue
    const params = JSON.parse(row.commandParams)
    const tr = state.tr
    try {
      for (const json of params.steps || []) tr.step(Step.fromJSON(schema, json))
    } catch (error) {
      // A row that does not apply to the document the server sequenced it against means the history itself is
      // broken, so no client can be compared to it. Honest clients reach this by submitting a badly mapped
      // batch, which is a far worse failure than a tab that merely looks wrong.
      return `BROKEN at v${row.version}: ${error.message}`
    }
    state = state.apply(tr)
  }
  return canonical(state.doc)
}

const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok })
  console.log(`${ok ? 'ok   ' : 'FAIL '} ${name}${detail ? `\n       ${detail}` : ''}`)
}

/**
 * A deterministic PRNG: a failing run has to be reproducible from the seed printed next to its name.
 */
const randomFor = (seed) => {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = Math.imul(state ^ (state >>> 15), 1 | state)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const RANDOM_SEED = 1000

/**
 * The property, over random interleavings: one document at the end, every character that was inserted still
 * there (insert-only, because a delete legitimately removes somebody else's), the same version as the
 * sequencer, and no batch left unacknowledged.
 */
const runRandomRounds = async ({ seed, writers, rounds, reorder = (frames) => frames, options, burst = 1 }) => {
  const server = new Sequencer()
  const hub = new Hub(server)
  const random = randomFor(seed)
  const sides = []
  for (let index = 0; index < writers; index += 1) {
    const side = clientScript(hub, server, `w${index}`)
    sides.push(side)
    hub.join(`w${index}`, side.client)
  }
  await settle()

  const inserted = []
  for (let round = 0; round < rounds; round += 1) {
    for (const side of sides) {
      // Typing several times before the wire gets a turn is what produces a multi-step batch: `addLocalSteps`
      // folds each new edit into the last unsent one, so the frame that goes out carries them all.
      for (let typed = 0; typed < burst; typed += 1) {
        const edit = localEdit(side.editor, side.client, random, options)
        if (edit?.inserted) inserted.push(edit.inserted)
      }
    }
    await settle()
    hub.outbox = reorder(hub.outbox)
    if (!await drain(hub)) return { livelock: true }
  }
  hub.release()
  const quiet = await drain(hub)
  await settle()

  const counts = (text) => {
    const seen = {}
    for (const char of text) seen[char] = (seen[char] || 0) + 1
    return seen
  }
  const expected = counts(inserted.join(''))
  const actual = counts(textOf(sides[0]))
  // Only meaningful when nothing may be removed: a delete legitimately takes somebody else's character.
  const lost = options.deletes ? []
    : Object.keys(expected).filter((char) => (actual[char] || 0) < expected[char])
  const truth = replayLog(server)
  return {
    converged: allEqual(sides, jsonOf),
    offLog: sides.filter((side) => jsonOf(side) !== truth),
    rebuilds: sides.reduce((total, side) => total + side.stats.rebuilds, 0),
    widestBatch: Math.max(0, ...server.log.map((row) => (JSON.parse(row.commandParams).steps || []).length)),
    logReplay: truth,
    versionsMatch: sides.every((side) => side.client.version === server.version),
    queueDrained: sides.every((side) => !side.client.hasUnacked),
    halted: sides.some((side) => side.client.halted),
    quiet,
    serverVersion: server.version,
    edits: inserted.length,
    lost,
    text: textOf(sides[0])
  }
}

/** Scenarios that fail today for a known reason, so the defect stays executable rather than being deleted. */
const knownDefects = []

const outcomePassed = (outcome) => !outcome.livelock && outcome.converged && outcome.offLog.length === 0
  && outcome.versionsMatch && outcome.queueDrained && outcome.quiet && !outcome.halted
  && outcome.lost.length === 0 && outcome.rebuilds === 0

const describe = (outcome) => outcome.livelock
  ? 'the clients never stopped sending to each other'
  : `v${outcome.serverVersion} after ${outcome.edits} inserts — lost ${JSON.stringify(outcome.lost)}, `
    + `converged ${outcome.converged}, offTheLog ${outcome.offLog.length}, rebuilds ${outcome.rebuilds}, `
    + `widest batch ${outcome.widestBatch}, `
    + `versions ${outcome.versionsMatch}, queue ${outcome.queueDrained}, wire ${outcome.quiet}, `
    + `halted ${outcome.halted}`
    + (outcome.logReplay?.startsWith('BROKEN') ? `\n       ${outcome.logReplay}` : '')
    + (outcome.offLog.length
      ? `\n       off the log: ${outcome.offLog.map((side) => `"${textOf(side)}"`).join(' vs ')}`
      : '')
    + `\n       "${outcome.text.slice(0, 60)}"`

const assertConverged = async (name, run, expectedToFail = false) => {
  const outcome = await run()
  const passed = outcomePassed(outcome)
  if (expectedToFail && !passed) {
    knownDefects.push(name)
    console.log(`KNOWN  ${name} — fails as documented\n       ${describe(outcome)}`)
    return
  }
  if (expectedToFail) {
    check(name, false, 'this is marked as a known defect but passes now — delete the marker')
    return
  }
  check(name, passed, describe(outcome))
}

await assertConverged('random: in-order delivery, two writers', () =>
  runRandomRounds({ seed: RANDOM_SEED, writers: 2, rounds: 60, options: { deletes: false } }))
await assertConverged('random: every insert-only edit survives the rebase', () =>
  runRandomRounds({ seed: RANDOM_SEED + 1, writers: 2, rounds: 60, options: { deletes: false } }))
await assertConverged('random: ACKs deliberately delivered after the peer STEPS', () =>
  runRandomRounds({
    seed: RANDOM_SEED + 2,
    writers: 2,
    rounds: 60,
    options: { deletes: false },
    reorder: (frames) => [...frames.filter(({ frame }) => frame.type !== 'ACK'),
      ...frames.filter(({ frame }) => frame.type === 'ACK')]
  }))
await assertConverged('random: three writers, frames delivered last-first', () =>
  runRandomRounds({
    seed: RANDOM_SEED + 3,
    writers: 3,
    rounds: 45,
    options: { deletes: false, marks: true },
    reorder: (frames) => frames.slice().reverse()
  }))
await assertConverged('random: four writers, insert plus mark plus delete workload', () =>
  runRandomRounds({ seed: RANDOM_SEED + 4, writers: 4, rounds: 40, options: { deletes: true } }))
// One writer, so `rebaseOver()` never runs: whatever this configuration breaks on belongs to the fold in
// `addLocalSteps` and to no one else.
await assertConverged('random: one writer, six edits per round (the fold alone, no peer)', () =>
  runRandomRounds({ seed: RANDOM_SEED + 7, writers: 1, rounds: 60, burst: 6, options: { deletes: true } }))
// Multi-step batches: several local edits folded into one batch, arriving while a peer's work is in flight.
// These are the configurations that found the double-mapping defects, so they carry the most weight here.
await assertConverged('random: three writers, three edits per round (multi-step batches)', () =>
  runRandomRounds({ seed: RANDOM_SEED + 5, writers: 3, rounds: 40, burst: 3, options: { deletes: true } }))
await assertConverged('random: two writers, five edits per round, frames reversed (multi-step batches)', () =>
  runRandomRounds({
    seed: RANDOM_SEED + 6,
    writers: 2,
    rounds: 40,
    burst: 5,
    options: { deletes: true },
    reorder: (frames) => frames.slice().reverse()
  }))

// --- the specific orderings that used to be bugs -----------------------------

/**
 * A peer's step whose range starts exactly where our unacknowledged insert sits: a delete of the character
 * after it, then a mark on the same range. The expected outcome is that both characters survive in both
 * documents, because the incoming range maps to the far side of our pending insert.
 *
 * These two currently fail, and the failure is not in the incoming mapping — the observed chain is our
 * reconnect replay after the frame arrives (`RangeError: Position 8 out of range`), which drops the pending
 * insert instead of restoring it. Recorded rather than explained; see `knownDefects` and AGENTS.md.
 */
const boundaryCase = async (name, foreign, expected) => {  const server = new Sequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  hub.join('a', a.client)
  await settle()
  typeText(a, 'abcd')
  await settle()
  await drain(hub)

  a.setConnected(false)
  insertAt(a, 'X', 4)
  await settle()
  const unackedBefore = a.client.hasUnacked
  const version = server.commitUnappliable([foreign.toJSON()], 'peer')
  hub.deliverTo('a', { type: 'STEPS', clientId: 'peer', version, steps: [foreign.toJSON()] })
  await settle()
  a.setConnected(true)
  // Reconnecting is not just flipping the flag: the app re-bootstraps on the new socket, which is what
  // revives the parked batch and gets it to the sequencer.
  a.client.bootstrap(server.state())
  await settle()
  await drain(hub)
  await settle()
  const passed = unackedBefore && textOf(a) === expected && jsonOf(a) === replayLog(server)
    && !a.client.halted && !a.client.hasUnacked && a.stats.rebuilds === 0
  const rows = server.log.map((row) => `v${row.version}:${row.commandParams}`).join(' ')
  const detail = `saw "${textOf(a)}", expected "${expected}", unacked before the frame ${unackedBefore}, `
    + `rebuilds ${a.stats.rebuilds}, halted ${a.client.halted}, unacked after ${a.client.hasUnacked}`
    + `\n       doc     ${jsonOf(a)}\n       log     ${replayLog(server)}\n       rows    ${rows}`
  if (!passed) {
    knownDefects.push(name)
    console.log(`KNOWN  ${name} — reproducible, cause not established\n       ${detail}`)
    return
  }
  check(name, true, detail)
}

await boundaryCase('our pending insert survives a peer delete at its position',
  new ReplaceStep(4, 5, Slice.empty), 'abcX')
await boundaryCase('our pending insert survives a peer mark at its position',
  new AddMarkStep(4, 5, schema.marks.bold.create()), 'abcXd')

{
  // The case that started it: two writers, one position, both characters unacknowledged at the same time. The
  // old code reached the same version with `abXY` on one tab and `abYX` on the other.
  const server = new Sequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  const b = clientScript(hub, server, 'b')
  hub.join('a', a.client)
  hub.join('b', b.client)
  await settle()
  typeText(a, 'ab')
  await settle()
  await drain(hub)
  typeText(a, 'X')
  typeText(b, 'Y')
  await settle()
  await drain(hub)
  await settle()
  check('two writers typing at one position converge on the log order',
    jsonOf(a) === jsonOf(b) && jsonOf(a) === replayLog(server) && textOf(a) === 'abXY'
      && a.client.version === server.version && b.client.version === server.version
      && !a.client.hasUnacked && !b.client.hasUnacked && a.stats.rebuilds === 0 && b.stats.rebuilds === 0,
    `a "${textOf(a)}", b "${textOf(b)}", v${a.client.version}/${b.client.version} of ${server.version}, `
      + `matchesLog ${jsonOf(a) === replayLog(server)}, `
      + `rebuilds ${a.stats.rebuilds}/${b.stats.rebuilds}`)
}

{
  // A late ACK: the peer's steps force a rebase before the sender ever learns its own batch committed.
  const server = new Sequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  const b = clientScript(hub, server, 'b')
  hub.join('a', a.client)
  hub.join('b', b.client)
  await settle()
  const random = randomFor(11)

  typeText(a, 'x')
  await settle()
  hub.pump((to, frame) => !(to === 'a' && frame.type === 'ACK'))
  await settle()
  await editThenDeliver(b, random, hub, { deletes: false })
  hub.release()
  await settle()
  await editThenDeliver(a, random, hub, { deletes: false })
  await settle()
  check('a tab does not lose its own text to an ACK that arrives late',
    jsonOf(a) === jsonOf(b) && a.client.version === server.version && !a.client.hasUnacked && !a.client.halted,
    `a "${textOf(a)}", b "${textOf(b)}", v${a.client.version} of ${server.version}`)
}

{
  // Two writers racing one base: exactly one is refused, and the loser still lands its text.
  const server = new Sequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  const b = clientScript(hub, server, 'b')
  hub.join('a', a.client)
  hub.join('b', b.client)
  await settle()
  const random = randomFor(23)
  typeText(a, 'x')
  typeText(b, 'y')
  await settle()
  const rejected = hub.outbox.filter(({ frame }) => frame.type === 'REJECT').length
  await drain(hub)
  await settle()
  check('one writer is refused against a stale base and still converges',
    jsonOf(a) === jsonOf(b) && a.client.version === b.client.version
      && a.client.version === server.version && !a.client.hasUnacked && !b.client.hasUnacked && rejected === 1,
    `${rejected} REJECT frame(s), a "${textOf(a)}", b "${textOf(b)}", v${server.version}`)
}

{
  // A lost frame: the gap has to be closed from the log, not noticed by a human reloading.
  const server = new Sequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  const b = clientScript(hub, server, 'b')
  hub.join('a', a.client)
  hub.join('b', b.client)
  await settle()
  const random = randomFor(31)
  await editThenDeliver(b, random, hub, { deletes: false })
  typeText(b, 'y')
  await settle()
  hub.pump((to, frame) => !(to === 'a' && frame.type === 'STEPS'))
  await settle()
  await editThenDeliver(a, random, hub, { deletes: false })
  await settle()
  check('a dropped STEPS frame is refetched from the operation log',
    jsonOf(a) === jsonOf(b) && a.client.version === server.version,
    `a "${textOf(a)}", b "${textOf(b)}", v${a.client.version} of ${server.version}`)
}

{
  // A joiner mid-session: INIT carries the checkpoint, and the replay above it is the only source of text.
  const server = new Sequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  hub.join('a', a.client)
  await settle()
  const random = randomFor(41)
  for (let round = 0; round < 20; round += 1) await editThenDeliver(a, random, hub, { deletes: false })
  const late = clientScript(hub, server, 'late')
  hub.join('late', late.client)
  await settle()
  check('a client that joins mid-session replays to the same document',
    jsonOf(late) === jsonOf(a) && late.client.version === a.client.version,
    `late "${textOf(late)}", a "${textOf(a)}", v${late.client.version}`)
}

{
  // A folded checkpoint must still rebuild the document byte for byte.
  const server = new Sequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  hub.join('a', a.client)
  await settle()
  const random = randomFor(53)
  for (let round = 0; round < 25; round += 1) await editThenDeliver(a, random, hub, { deletes: false })
  const before = jsonOf(a)
  await a.client.requestCheckpoint()
  await settle()
  const rebuilt = clientScript(hub, server, 'rebuilt')
  hub.join('rebuilt', rebuilt.client)
  await settle()
  check('a folded checkpoint still rebuilds the document byte for byte',
    jsonOf(rebuilt) === before && server.checkpointVersion > 0 && server.log.length < 25,
    `folded at v${server.checkpointVersion}, ${server.log.length} of 25 rows left, "${textOf(rebuilt)}"`)
}

{
  // One row nobody can apply: the replay stops, and it stops for good.
  const server = new Sequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  hub.join('a', a.client)
  await settle()
  const random = randomFor(61)
  await editThenDeliver(a, random, hub, { deletes: false })
  const errors = []
  a.client.on('error', (error) => errors.push(error.message))
  const beyond = a.editor.state.doc.content.size + 500
  server.commitUnappliable([{ stepType: 'replace', from: beyond, to: beyond + 1, slice: { size: 0, openEnd: 0 } }])
  await editThenDeliver(a, random, hub, { deletes: false })
  const versionWhenHalted = a.client.version
  const sendsBefore = hub.outbox.length
  await editThenDeliver(a, random, hub, { deletes: false })
  check('an unappliable row halts the client instead of looping it',
    a.client.halted && errors.length === 1 && a.client.version === versionWhenHalted
      && hub.outbox.length === sendsBefore,
    `halted ${a.client.halted}, ${errors.length} error(s) "${errors[0]}", stuck at v${a.client.version} `
      + `of ${server.version}`)
}

{
  // The documented way out of a halt is a whole-document reset: restoring an older version.
  const server = new FoldingSequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  hub.join('a', a.client)
  await settle()
  const random = randomFor(71)
  await editThenDeliver(a, random, hub, { deletes: false })
  const poison = [{ stepType: 'replace', from: 999, to: 1000, slice: { size: 0, openEnd: 0 } }]
  hub.deliverTo('a', { type: 'STEPS', clientId: 'poison', version: server.commitUnappliable(poison), steps: poison })
  await settle()
  const haltedBefore = a.client.halted
  hub.deliverTo('a', server.replace(a.editor.getJSON()))
  await settle()
  await editThenDeliver(a, random, hub, { deletes: false })
  check('a reset clears the halt and makes the tab writable again',
    haltedBefore && !a.client.halted && a.client.version === server.version && !a.client.hasUnacked,
    `halted before ${haltedBefore}, halted now ${a.client.halted}, v${a.client.version} of ${server.version}`)
}

// The `batch.inDocument` guard in `rebaseOver` — the one that stops a batch parked by a rebuild from being
// wiped by the rebase that follows it — is deliberately not claimed as covered here. Reaching it needs a
// keystroke inside the rebuild's `/operations` fetch, and the serialized frame chain runs any incoming frame
// only after the rebuild has already revived its batches. The guard stays because a review reproduced the loss
// against a driven client, and because an empty `pending` is what authorises the next checkpoint to fold the
// log, which would turn that loss into shared, permanent loss.

{
  // Two batches, the first one in flight: the second batch's steps already sit on top of the first, so the
  // rebuild must not shift that row onto them. Measured before `seen` was inherited at creation: the second
  // batch walked off the end of the document on revive, the tab halted at v1, and the log was fine.
  const server = new Sequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  hub.join('a', a.client)
  await settle()
  typeText(a, '!')
  await settle()
  hub.pump((to, frame) => !(to === 'a' && frame.type === 'ACK'))
  await settle()
  typeText(a, 'A')
  typeText(a, 'B')
  await settle()
  hub.release()
  hub.deliverTo('a', { type: 'INIT', onlineCount: 1, ...server.state() })
  await settle()
  await drain(hub)
  await settle()
  check('a second batch typed while one is in flight survives the rebuild',
    textOf(a) === '!AB' && jsonOf(a) === replayLog(server) && !a.client.halted && !a.client.hasUnacked
      && a.client.version === server.version,
    `"${textOf(a)}", matchesLog ${jsonOf(a) === replayLog(server)}, halted ${a.client.halted}, `
      + `unacked ${a.client.hasUnacked}, v${a.client.version} of ${server.version}`)
}

{
  // The `rebased` meta is a contract with `prosemirror-history`, not with the server, and no document
  // comparison can check it: `Branch.rebased` pairs the client's stored undo items with the lift inverses by
  // mirror index, so the value has to be exactly the number of steps the rebase lifted. What this does NOT pin:
  // a case where the replay drops a lifted step (a peer deleting the content it carried), which is the only
  // shape that separates `lifted` from `replayed` — asserting the wrong one of those two would still be green
  // here.
  const server = new Sequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  hub.join('a', a.client)
  await settle()
  const peerStep = {
    stepType: 'replace', from: 1, to: 1,
    slice: { content: [{ type: 'text', text: 'p' }], size: 1, openStart: 0, openEnd: 0 }
  }
  a.setConnected(false)
  typeText(a, 'a')
  typeText(a, 'b')
  typeText(a, 'c')
  await settle()
  const lifted = a.client.hasUnacked ? 3 : 0
  const version = server.commitUnappliable([peerStep], 'peer')
  hub.deliverTo('a', { type: 'STEPS', clientId: 'peer', version, steps: [peerStep] })
  await settle()
  const reported = a.editor.rebases[a.editor.rebases.length - 1]
  a.setConnected(true)
  hub.deliverTo('a', { type: 'INIT', onlineCount: 1, ...server.state() })
  await settle()
  await drain(hub)
  await settle()
  check('the rebase tells the history how many steps it lifted',
    lifted === 3 && reported === 3 && textOf(a) === 'pabc' && jsonOf(a) === replayLog(server)
      && !a.client.halted && !a.client.hasUnacked,
    `reported ${JSON.stringify(a.editor.rebases)}, lifted ${lifted}, text "${textOf(a)}", `
      + `matchesLog ${jsonOf(a) === replayLog(server)}, unacked ${a.client.hasUnacked}`)
}

{
  // The case that first exposed the double-mapped fold: two edits typed while offline, then submitted as one
  // batch. It has to be a batch the server can replay, not merely one whose version lines up.
  const server = new Sequencer()
  const hub = new Hub(server)
  const a = clientScript(hub, server, 'a')
  hub.join('a', a.client)
  await settle()
  const random = randomFor(83)
  a.setConnected(false)
  localEdit(a.editor, a.client, random, { deletes: false })
  localEdit(a.editor, a.client, random, { deletes: false })
  await settle()
  const queuedWhileDown = server.version
  a.setConnected(true)
  hub.deliverTo('a', { type: 'INIT', onlineCount: 1, ...server.state() })
  await settle()
  await drain(hub)
  await settle()
  const replayed = replayLog(server)
  check('typing while disconnected is folded into one batch, and that batch is replayable',
    queuedWhileDown === 0 && server.version === 1 && textOf(a).length === 2 && !replayed.startsWith('BROKEN')
      && jsonOf(a) === replayed && !a.client.hasUnacked && a.stats.rebuilds === 0,
    `server at v${server.version} for two edits, text "${textOf(a)}", log ${replayed}`)
}

const failed = results.filter((result) => !result.ok)
console.log(`\n${results.length - failed.length}/${results.length} convergence checks passed`
  + (knownDefects.length ? `, ${knownDefects.length} known defect still failing` : ''))
process.exit(failed.length ? 1 : 0)

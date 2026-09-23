import { Mapping, Step } from '@tiptap/pm/transform'

/**
 * Client half of the collaboration protocol. The server only orders step batches, so every
 * convergence guarantee lives here.
 *
 * The invariant most of this file exists to keep: every step in `pending` carries the inverse of itself, so an
 * incoming batch can be applied by *rebasing* — lift our unacknowledged work out, apply theirs exactly as the
 * log has it, replay ours on top. Mapping in both directions was tried and is wrong: a batch's steps are
 * successive relative to one another, so one accumulated mapping shifts them twice, and a same-position tie
 * broke in opposite orders on the two clients. `inDocument` still distinguishes the batches whose steps are in
 * this document right now from the ones a bootstrap parked; parked steps are replayed, never lifted.
 *
 * Other rules that are not optional:
 * - local steps join `pending` **synchronously** (ProseMirror has already applied them); only the send is
 *   serialized onto the promise chain, otherwise steps typed during an awaited fetch would be invisible to
 *   `rebaseOver` and incoming steps would land at the wrong offsets;
 * - at most one batch is outstanding, so acknowledgement bookkeeping stays trivial; later local steps append to
 *   the unsent batch unchanged, because ProseMirror already applied them on top of it;
 * - frames are processed through one serialized chain because the server fans frames out after commit, so
 *   two writers' frames can arrive out of order; a version gap is closed by refetching
 *   `GET /{id}/operations?after=`;
 * - a bootstrap replaces the document, so whatever we applied but cannot be shown to have committed has to
 *   be re-applied afterwards or the user loses the text typed while the socket was down.
 */
export function createCollabClient({ editor, documentId, clientId, api, ws }) {
  let version = 0
  let contentFormat = 'html'
  let pending = []
  let outstanding = null
  let checkpointDue = false
  let tail = Promise.resolve()
  let destroyed = false
  let haltedAt = null

  const listeners = { version: [], presence: [], reset: [], error: [] }
  const emit = (kind, payload) => listeners[kind].forEach((fn) => fn(payload))
  const on = (kind, fn) => {
    listeners[kind].push(fn)
    return () => {
      listeners[kind] = listeners[kind].filter((registered) => registered !== fn)
    }
  }

  const enqueue = (task) => {
    tail = tail.then(() => {
      if (destroyed) return
      return task().catch(async (error) => {
        console.error('collab: falling back to full resync after', error)
        try {
          await bootstrapNow(await fetchState())
        } catch (resyncError) {
          emit('error', resyncError)
        }
      })
    })
    return tail
  }

  const unackedEntries = () =>
    pending.filter((batch) => batch.inDocument).flatMap((batch) => batch.entries)

  /**
   * The inverse of a step is only meaningful against the document it was applied to, and ProseMirror keeps
   * exactly that in `tr.docs[i]` — which is why local capture receives the transaction, not just its steps.
   */
  const entriesFrom = (tr, batch) =>
    tr.steps.map((step, index) => ({ step, inverted: step.invert(tr.docs[index]), batch }))

  const stepsFrom = (raw) => {
    const schema = editor.state.schema
    return raw.map((json) => Step.fromJSON(schema, json))
  }

  const remoteMetas = (tr) => {
    tr.setMeta('addToHistory', false)
    tr.setMeta('remote', true)
    return tr
  }

  const applySteps = (steps) => {
    if (!steps.length) return
    const tr = remoteMetas(editor.state.tr)
    // `step`, not `addStep`: in this ProseMirror version `addStep(step, doc)` is the internal half that
    // stores the document the *caller* computed, so calling it with one argument sets the transaction's doc
    // to undefined and the very next thing ProseMirror does is resolve the selection against it.
    steps.forEach((step) => tr.step(step))
    editor.view.dispatch(tr)
  }

  /**
   * Lifts our unacknowledged steps out of the document, applies the incoming ones as the log has them, then
   * replays ours on top. Ported from `prosemirror-collab`'s `rebaseSteps`.
   *
   * Mapping in both directions cannot express this, which is what the last two defects were. Each side's steps
   * are successive relative to themselves, so mapping an incoming step through *all* of our accumulated maps
   * puts it on the wrong base, and a same-position tie then breaks differently on the two clients — the log
   * said `X@3` then `Y@4`, one tab showed `abXY` and the other `abYX`, both at the same version with nothing
   * halted. Replaying ours through `mapping.slice(mapFrom)` with the mirror set is what keeps the order the
   * server chose: ours goes after theirs, always, because ours has no sequence number yet.
   *
   * A lift that throws or a replay that no longer applies is a step whose content another writer removed;
   * `maybeStep` drops it, matching upstream. The incoming steps are applied with `step`, so an unappliable
   * history row still reaches `haltAt` rather than being skipped.
   */
  const rebaseOver = (incoming, atVersion = null) => {
    const overTheirs = new Mapping(incoming.map((step) => step.getMap()))

    // A parked batch's steps are expressed against the version it was built on, so everything the replay
    // applies above that version has to shift them as well. Revive them verbatim and they land at offsets from
    // a document that no longer exists — consistent with the log, and wrong in it, because the stale offset is
    // what gets submitted next.
    if (atVersion !== null) {
      pending.forEach((batch) => {
        if (batch.inDocument || batch.base >= atVersion) return
        batch.entries = batch.entries.flatMap((entry) => {
          const mapped = entry.step.map(overTheirs)
          return mapped ? [{ step: mapped, inverted: entry.inverted, batch }] : []
        })
      })
    }

    const ours = unackedEntries()
    if (!ours.length) {
      applySteps(incoming)
      return
    }

    const tr = remoteMetas(editor.state.tr)
    for (let index = ours.length - 1; index >= 0; index -= 1) tr.step(ours[index].inverted)
    incoming.forEach((step) => tr.step(step))

    const replayed = []
    for (let index = 0, mapFrom = ours.length; index < ours.length; index++) {
      const mapped = ours[index].step.map(tr.mapping.slice(mapFrom))
      mapFrom--
      if (!mapped) continue
      const before = tr.doc
      if (tr.maybeStep(mapped).failed) continue
      tr.mapping.setMirror(mapFrom, tr.steps.length - 1)
      replayed.push({ step: mapped, inverted: mapped.invert(before), batch: ours[index].batch })
    }

    // Only the batches that were lifted get a new entry list. A parked one is not in the document, was never
    // lifted, and is still waiting for `bootstrapNow` to revive it — overwriting its entries here would delete
    // the user's offline typing without a trace, and then fold that into everyone's history at the next
    // checkpoint, because an empty `pending` is what lets a checkpoint go out at all.
    pending.forEach((batch) => {
      if (batch.inDocument) batch.entries = replayed.filter((entry) => entry.batch === batch)
    })
    pending = pending.filter((batch) => !batch.inDocument || batch.entries.length)
    if (outstanding && outstanding.inDocument && !outstanding.entries.length) outstanding = null
    editor.view.dispatch(tr)
  }

  const sendBatch = (batch) => {
    batch.sent = true
    outstanding = batch
    ws.send({
      type: 'STEP_BATCH',
      clientId,
      baseVersion: batch.base,
      docSize: editor.state.doc.content.size,
      steps: batch.entries.map((entry) => entry.step.toJSON())
    })
  }

  const flush = () => {
    if (haltedAt !== null || outstanding || !ws.isConnected()) return
    const next = pending.find((batch) => !batch.sent && batch.inDocument)
    if (next) {
      // Everything earlier is acknowledged, so the server's current version is this batch's base.
      next.base = version
      sendBatch(next)
    }
  }

  /**
   * Called with every transaction a local edit produced, after ProseMirror has applied them. TipTap hands the
   * root transaction and the `appendTransaction` output separately, and StarterKit's trailing paragraph is one
   * of the appended ones — a step that reaches the document but not this batch is content no peer will ever
   * receive, and the next lift will throw because `pending` does not know to undo it.
   *
   * Steps enter `pending` **synchronously** and append unchanged: each is already successive, because it was
   * applied on top of the ones before it.
   */
  const addLocalSteps = (transactions) => {
    transactions.filter((tr) => tr.steps.length).forEach((tr) => {
      const last = pending[pending.length - 1]
      if (last && !last.sent && last.inDocument && last.base === version) {
        last.entries = last.entries.concat(entriesFrom(tr, last))
      } else {
        const batch = { base: version, entries: [], sent: false, inDocument: true }
        batch.entries = entriesFrom(tr, batch)
        pending.push(batch)
      }
    })
    enqueue(async () => flush())
  }

  const fetchState = async () => {
    const { data } = await api.get(`/api/documents/${documentId}`)
    return data
  }

  /**
   * Ends the replay at a row this client cannot apply.
   *
   * The server orders step JSON without interpreting it, so any writer can commit a batch no reader can
   * apply — a hand-built offset, a slice that does not fit, a client working from a document it never
   * fetched. Retrying that is not a delay but a loop: the rebuild replays the same row, fails the same way,
   * and the tab never becomes usable again. So the row is recorded, everything above it is left unapplied,
   * the local queue stops going out, and the user sees where the content stopped.
   *
   * This is a truthful dead end, not a repair: the tail genuinely cannot be replayed, and only someone with
   * write access restoring an older version can make the document whole again.
   */
  const haltAt = (rowVersion, error) => {
    if (haltedAt !== null) return
    haltedAt = rowVersion
    console.error(`collab: history cannot be replayed past v${rowVersion}`, error)
    emit('error', new Error(
      `无法重放 v${rowVersion} 之后的历史，内容停在 v${version}；这里的后续编辑不会被保存，刷新页面可重试`))
  }

  /**
   * Proves that one specific batch reached the log, and only from the version that proves it.
   *
   * The server refuses any batch whose base is not the current version and commits the accepted one at
   * exactly `base + 1`, so a row, frame or acknowledgement carrying that version is the proof. A flag for
   * "we saw some row of ours" cannot stand in for it: a replay spans everything above the checkpoint, which
   * is up to `CHECKPOINT_EVERY` versions of older work, and dropping or reviving on that evidence loses or
   * duplicates the batch the user is waiting on.
   */
  const acknowledge = (committedVersion) => {
    const batch = pending.find((candidate) => candidate.sent && candidate.base + 1 === committedVersion)
    if (!batch) return
    pending = pending.filter((candidate) => candidate !== batch)
    if (outstanding === batch) outstanding = null
  }

  /**
   * Applies the log rows above `version`.
   *
   * `replayOwn` distinguishes the callers: after a bootstrap the document only holds content up to the
   * checkpoint, so rows we authored must be replayed too; mid-session our own rows are already in the
   * document, and replaying them would duplicate text. Either way the row proves that batch committed.
   */
  const catchUp = async (throughVersion, { replayOwn = false } = {}) => {
    const { data } = await api.get(`/api/documents/${documentId}/operations`, {
      params: { after: version }
    })
    for (const log of data) {
      if (log.version <= version) continue
      // The endpoint returns everything after our version, which includes the frame that made us
      // refetch. Applying it here and again in the caller inserts the same text twice, so stop at the
      // version we were asked to catch up to.
      if (log.version > throughVersion) break
      if (log.commandType !== 'STEPS') {
        // A whole-document replace happened above us: only a fresh checkpoint can rebuild from it.
        await bootstrapNow(await fetchState())
        return
      }
      const params = JSON.parse(log.commandParams)
      try {
        const steps = stepsFrom(params.steps || [])
        if (params.clientId === clientId) {
          acknowledge(log.version)
          // Our own row is still somebody else's batch as far as this document is concerned: the user may have
          // typed while the network call was in flight, and replaying the row at its original offsets would
          // land it inside that work instead of beside it.
          if (replayOwn) rebaseOver(steps, log.version)
        } else {
          rebaseOver(steps, log.version)
        }
      } catch (error) {
        haltAt(log.version, error)
        return
      }
      version = log.version
    }

    if (version < throughVersion) throw new Error(`operations log stopped at ${version}`)
  }

  const handleSteps = (frame) =>
    enqueue(async () => {
      if (haltedAt !== null) return
      const seq = frame.version
      if (seq <= version) return
      if (seq > version + 1) await catchUp(seq - 1)
      // The gap may have ended in a halt. Continuing would apply this frame and adopt its version, so the tab
      // would hold a version whose content it never received — and the next checkpoint it uploads folds away
      // exactly the rows it skipped.
      if (haltedAt !== null) return
      // Closing that gap can rebuild the whole document (a nested bootstrap adopts the newest version), in
      // which case this frame's own steps are already applied.
      if (seq <= version) return

      if (frame.clientId === clientId) {
        acknowledge(seq)
      } else {
        rebaseOver(stepsFrom(frame.steps || []), seq)
      }
      // Never lower it: a bootstrap nested inside that catchUp may already have adopted a newer version
      // than the frame we are finishing, and downgrading would make the next replay re-apply rows that are
      // already in the document.
      version = Math.max(version, seq)

      emit('version', version)
      flush()
    })

  const handleAck = (frame) =>
    enqueue(async () => {
      if (haltedAt !== null) return
      if (frame.clientId !== clientId) return
      // The batch may already have been dropped (all its steps deleted by a concurrent edit), but its
      // version still has to be adopted or every later batch would be rejected against a stale base.
      acknowledge(frame.version)
      version = Math.max(version, frame.version)
      emit('version', version)
      if (frame.checkpointRequested) checkpointDue = true
      if (checkpointDue && !pending.length) {
        checkpointDue = false
        await uploadCheckpoint()
      }
      flush()
    })

  const handleReject = (frame) =>
    enqueue(async () => {
      if (haltedAt !== null) return
      if (frame.clientId !== clientId || !outstanding) return
      // The version a rejection carries is the server's current one, so anything at or below this batch's
      // base is answering an older batch — reverting the current one would have it sent twice. Our view of
      // the version is then wrong, and rebuilding is the only way out: returning here would wedge the tab
      // behind a batch it can neither send nor abandon.
      if (frame.version <= outstanding.base) return bootstrapNow(await fetchState())
      const rejected = outstanding
      rejected.sent = false
      outstanding = null
      // It stays at the head of `pending` while we catch up, so the rebase inside `catchUp` keeps its steps
      // aligned with the operations we are about to apply; sending it at its old offsets would corrupt the
      // document.
      await catchUp(frame.version)
      if (haltedAt !== null) return
      rejected.base = version
      if (!rejected.entries.length) pending = pending.filter((batch) => batch !== rejected)
      emit('version', version)
      flush()
    })

  const uploadCheckpoint = async () => {
    // While halted, this client's version describes a document it cannot reproduce: snapshotting it would
    // fold away the rows above the poison for everybody, replacing history this tab never read.
    if (haltedAt !== null) return
    // Uploading while local steps are unacknowledged would burn private work into the snapshot at a
    // version that does not contain it, and everyone would replay that work twice.
    if (pending.length) {
      checkpointDue = true
      return
    }
    try {
      await api.post(`/api/documents/${documentId}/checkpoint`, {
        atSeq: version,
        content: JSON.stringify(editor.getJSON()),
        contentFormat: 'doc-json'
      })
    } catch (error) {
      // A newer version already exists: this checkpoint simply was not the one to fold the log.
      if (error.response?.status !== 409) emit('error', error)
    }
  }

  const setContent = (raw, format, nextVersion) => {
    contentFormat = format || 'html'
    const parsed = contentFormat === 'doc-json' && raw ? JSON.parse(raw) : (raw || '')
    // emitUpdate stays off: this content is the agreed state, so it must not come back as a step batch.
    editor.commands.setContent(parsed, { emitUpdate: false })
    version = nextVersion
    emit('version', version)
  }

  /** Re-applies parked steps to a rebuilt document and re-derives their inverses against the new one. */
  const applyEntries = (steps, batch) => {
    if (!steps.length) return []
    const tr = remoteMetas(editor.state.tr)
    steps.forEach((step) => tr.step(step))
    const entries = entriesFrom(tr, batch)
    editor.view.dispatch(tr)
    return entries
  }

  const bootstrapNow = async (state) => {
    // The document is about to be replaced, so none of our batches are in it any more — but they stay in
    // `pending`, because `rebaseOver` still has to lift their steps out and replay them over everything the
    // rebuild applies. Anything typed during this function is the exception: ProseMirror applies it to the new
    // document straight away, so it keeps `inDocument` and must not be applied twice by the loop below.
    pending.forEach((batch) => { batch.inDocument = false })
    checkpointDue = false

    setContent(state.content, state.contentFormat ?? 'html', state.checkpointVersion ?? state.version)
    emit('presence', state.onlineCount)
    if (state.version > version) await catchUp(state.version, { replayOwn: true })
    // A halt mid-replay means this document's tail cannot be read at all, so the rebuild stopped short of
    // `state.version`. Reviving parked batches onto a half-rebuilt document would place them at offsets that
    // no longer exist — stay frozen instead, and let someone restore an older version.
    if (haltedAt !== null) return

    // A sent batch whose version the *checkpoint* has passed cannot be recovered from the log any more: rows
    // at or below it were folded and deleted when that checkpoint was recorded, and folding only ever
    // happens to rows that were committed — so its content is already in the document we just loaded, and
    // reviving it would duplicate text that the next upload then burns into everyone's history. Above the
    // checkpoint the replay answers the question instead: our own row proves it by identity in
    // `acknowledge`, and the absence of any row at that version means a peer won the race, so the batch is
    // still ours to resend.
    const rebuiltFrom = state.checkpointVersion ?? state.version
    const folded = (batch) => batch.sent && batch.base + 1 <= rebuiltFrom
    const waiting = pending.filter((batch) =>
      !batch.inDocument && !folded(batch) && batch.entries.length)
    // Keep whatever became `inDocument` while the replay was in flight — the user went on typing — and drop
    // everything else from the list before re-adding the revived batches on top.
    pending = pending.filter((batch) => batch.inDocument)
    outstanding = null
    for (const batch of waiting) {
      try {
        batch.entries = applyEntries(batch.entries.map((entry) => entry.step), batch)
      } catch (error) {
        // Our own parked work no longer applies to the document the rebuild produced. That is the same dead
        // end as an unappliable history row, and it gets the same treatment: stop, rather than loop through
        // another rebuild that will fail the same way.
        haltAt(version, error)
        return
      }
      batch.inDocument = true
      batch.sent = false
      batch.base = version
      pending.push(batch)
    }

    emit('version', version)
    flush()
  }

  const bootstrap = (state) => enqueue(() => bootstrapNow(state))

  const handleReset = (frame) =>
    enqueue(async () => {
      // A late, out-of-order reset must not downgrade the version or lift a halt belonging to newer history.
      if (frame.version < version) return
      // A reset replaces the whole document, so the row that halted the replay is no longer in anyone's
      // history: this is the one legitimate way out of a halt, and the tab must be able to use it.
      haltedAt = null
      pending = []
      outstanding = null
      setContent(frame.content, frame.contentFormat, frame.version)
      emit('reset', frame)
    })

  const destroy = () => {
    destroyed = true
    pending = []
    outstanding = null
  }

  return {
    addLocalSteps,
    handleSteps,
    handleAck,
    handleReject,
    handleReset,
    setContent,
    bootstrap,
    requestCheckpoint: () => enqueue(() => uploadCheckpoint()),
    on,
    get version() { return version },
    get contentFormat() { return contentFormat },
    get hasUnacked() { return pending.length > 0 },
    get halted() { return haltedAt !== null },
    destroy
  }
}

export const makeClientId = () =>
  `c-${Math.random().toString(36).slice(2, 8)}${Date.now().toString(36).slice(-4)}`

import { Step, Mapping } from '@tiptap/pm/transform'

/**
 * Client half of the collaboration protocol. The server only orders step batches, so every
 * convergence guarantee lives here.
 *
 * Ordering rules that are not optional:
 * - local steps join `pending` **synchronously** (ProseMirror has already applied them to the document);
 *   only the send is serialized onto the promise chain, otherwise steps typed during an awaited fetch
 *   would be invisible to `integrate()` and the incoming steps would land at the wrong offsets;
 * - at most one batch is outstanding, so acknowledgement bookkeeping stays trivial; later local steps
 *   fold into the unsent batch after mapping through its accumulated `Mapping`;
 * - incoming steps are mapped through our unacknowledged steps before being applied, and our steps are
 *   mapped through the incoming ones before being sent — both directions are required;
 * - frames are processed through one serialized chain because the server fans frames out after commit,
 *   so two writers' frames can arrive out of order; a version gap is closed by refetching
 *   `GET /{id}/operations?after=`;
 * - a bootstrap replaces the document, so whatever we had applied but never saw committed has to be
 *   re-applied afterwards or the user loses the text typed while the socket was down.
 */
export function createCollabClient({ editor, documentId, clientId, api, ws }) {
  let version = 0
  let contentFormat = 'html'
  let pending = []
  let outstanding = null
  let checkpointDue = false
  let sawOwnCommit = false
  let tail = Promise.resolve()
  let destroyed = false

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

  const unackedSteps = () => pending.flatMap((batch) => batch.steps)

  const mapThrough = (steps, maps) =>
    steps.map((step) => maps.reduce((current, map) => (current ? current.map(map) : null), step))
      .filter(Boolean)

  const stepsFrom = (raw) => {
    const schema = editor.state.schema
    return raw.map((json) => Step.fromJSON(schema, json))
  }

  const applySteps = (steps) => {
    if (!steps.length) return
    const tr = editor.state.tr
    steps.forEach((step) => tr.addStep(step))
    tr.setMeta('addToHistory', false)
    tr.setMeta('remote', true)
    editor.view.dispatch(tr)
  }

  /**
   * Interleaves one incoming batch with the steps that are applied locally but not yet acknowledged:
   * the incoming steps are mapped through ours so they land in this document, and ours are mapped
   * through theirs so they stay sendable against the server's order.
   */
  const integrate = (incomingSteps) => {
    const incomingMaps = incomingSteps.map((step) => step.getMap())
    const applicable = mapThrough(incomingSteps, unackedSteps().map((step) => step.getMap()))

    pending.forEach((batch) => {
      batch.steps = mapThrough(batch.steps, incomingMaps)
    })
    pending = pending.filter((batch) => batch.steps.length)
    if (outstanding && !outstanding.steps.length) outstanding = null

    return applicable
  }

  const sendBatch = (batch) => {
    batch.sent = true
    outstanding = batch
    ws.send({
      type: 'STEP_BATCH',
      clientId,
      baseVersion: batch.base,
      docSize: editor.state.doc.content.size,
      steps: batch.steps.map((step) => step.toJSON())
    })
  }

  const flush = () => {
    if (outstanding || !ws.isConnected()) return
    const next = pending.find((batch) => !batch.sent)
    if (next) {
      // Everything earlier is acknowledged, so the server's current version is this batch's base.
      next.base = version
      sendBatch(next)
    }
  }

  /** Called with the steps of a local transaction, after ProseMirror has already applied them. */
  const addLocalSteps = (steps) => {
    if (!steps.length) return

    const last = pending[pending.length - 1]
    if (last && !last.sent && last.base === version) {
      const mapping = new Mapping(last.steps.map((step) => step.getMap()))
      last.steps = last.steps.concat(steps.map((step) => step.map(mapping)).filter(Boolean))
    } else {
      pending.push({ base: version, steps: [...steps], sent: false })
    }
    enqueue(async () => flush())
  }

  const fetchState = async () => {
    const { data } = await api.get(`/api/documents/${documentId}`)
    return data
  }

  /**
   * Applies the log rows above `version`.
   *
   * `replayOwn` distinguishes the callers: after a bootstrap the document only holds content up to the
   * checkpoint, so rows we authored must be replayed too; mid-session our own rows are already in the
   * document, and replaying them would duplicate text.
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
      if (params.clientId === clientId) {
        sawOwnCommit = true
        if (!replayOwn) {
          dropAcknowledgedOutstanding()
        } else {
          applySteps(stepsFrom(params.steps || []))
        }
      } else {
        applySteps(integrate(stepsFrom(params.steps || [])))
      }
      version = log.version
    }

    if (version < throughVersion) throw new Error(`operations log stopped at ${version}`)
  }

  const dropAcknowledgedOutstanding = () => {
    if (!outstanding) return
    pending = pending.filter((batch) => batch !== outstanding)
    outstanding = null
  }

  const handleSteps = (frame) =>
    enqueue(async () => {
      const seq = frame.version
      if (seq <= version) return
      if (seq > version + 1) await catchUp(seq - 1)

      if (frame.clientId === clientId) {
        sawOwnCommit = true
        dropAcknowledgedOutstanding()
      } else {
        applySteps(integrate(stepsFrom(frame.steps || [])))
      }
      version = seq

      emit('version', version)
      flush()
    })

  const handleAck = (frame) =>
    enqueue(async () => {
      if (frame.clientId !== clientId) return
      // The batch may already have been dropped (all its steps deleted by a concurrent edit), but its
      // version still has to be adopted or every later batch would be rejected against a stale base.
      dropAcknowledgedOutstanding()
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
      if (frame.clientId !== clientId || !outstanding) return
      const rejected = outstanding
      rejected.sent = false
      outstanding = null
      // It stays at the head of `pending` while we catch up, so integrate() re-maps its steps over the
      // operations we are about to apply; sending it at its old offsets would corrupt the document.
      await catchUp(frame.version)
      rejected.base = version
      if (!rejected.steps.length) pending = pending.filter((batch) => batch !== rejected)
      emit('version', version)
      flush()
    })

  const uploadCheckpoint = async () => {
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

  const bootstrapNow = async (state) => {
    const carried = pending.filter((batch) => !batch.sent)
    const unacked = outstanding
    pending = carried
    outstanding = null
    checkpointDue = false
    sawOwnCommit = false

    setContent(state.content, state.contentFormat ?? 'html', state.checkpointVersion ?? state.version)
    emit('presence', state.onlineCount)
    if (state.version > version) await catchUp(state.version, { replayOwn: true })

    // `carried` steps were mapped by integrate() while catching up; the unacknowledged batch is only
    // re-applied when the log proves the server never committed it.
    if (unacked && !sawOwnCommit) {
      unacked.sent = false
      carried.push(unacked)
    }
    const revived = carried.filter((batch) => batch.steps.length)
    revived.forEach((batch) => {
      applySteps(batch.steps)
      batch.base = version
    })
    pending = revived

    emit('version', version)
    flush()
  }

  const bootstrap = (state) => enqueue(() => bootstrapNow(state))

  const handleReset = (frame) =>
    enqueue(async () => {
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
    resync: () => enqueue(async () => bootstrapNow(await fetchState())),
    requestCheckpoint: () => enqueue(() => uploadCheckpoint()),
    on,
    get version() { return version },
    get contentFormat() { return contentFormat },
    get hasUnacked() { return pending.length > 0 },
    destroy
  }
}

export const makeClientId = () =>
  `c-${Math.random().toString(36).slice(2, 8)}${Date.now().toString(36).slice(-4)}`

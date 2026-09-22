import { ref, computed } from 'vue'
import api from './api'

/**
 * Share invites have to be visible outside a document session, and the socket only exists while a
 * document is open, so this is a module-level store: the REST list is the source of truth, and a live
 * NOTIFICATION frame from an open document is folded in without waiting for a reload.
 */
const items = ref([])
const unreadCount = computed(() => items.value.filter((item) => !item.read).length)

const load = async () => {
  try {
    const { data } = await api.get('/api/notifications')
    items.value = data
  } catch (error) {
    console.error('Failed to load notifications:', error)
  }
}

const push = (frame) => {
  if (items.value.some((item) => item.id === frame.id)) return
  items.value = [{
    id: frame.id,
    documentId: frame.documentId,
    type: frame.notificationType,
    message: frame.message,
    read: false,
    createdAt: frame.createdAt
  }, ...items.value]
}

const markAllRead = async () => {
  try {
    await api.post('/api/notifications/read')
    items.value = items.value.map((item) => ({ ...item, read: true }))
  } catch (error) {
    console.error('Failed to mark notifications read:', error)
  }
}

const reset = () => { items.value = [] }

export default { items, unreadCount, load, push, markAllRead, reset }

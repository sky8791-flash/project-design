import { ref } from 'vue'
import api, { setUnauthorizedHandler, storeToken, TOKEN_EXPIRY_KEY, TOKEN_KEY } from './api'
import notifications from './notifications'
import wsService from './websocket'

/**
 * The signed-in user lives here rather than in `App.vue` so the router guard can read it: a refresh on
 * `/#/doc/5` or `/#/admin` has to be resolved before the view mounts. The bearer token is the only
 * credential the app sends; no endpoint accepts a self-declared user id any more.
 */
const currentUser = ref(null)

const restore = () => {
  const stored = localStorage.getItem('user')
  if (!stored || !localStorage.getItem(TOKEN_KEY)) return
  try {
    const parsed = JSON.parse(stored)
    if (parsed && parsed.id) currentUser.value = parsed
  } catch {
    localStorage.removeItem('user')
  }
}

const signIn = (user) => {
  currentUser.value = user
  localStorage.setItem('user', JSON.stringify(user))
  if (user.token) storeToken(user.token, user.expiresIn)
  notifications.load()
}

const signOut = () => {
  currentUser.value = null
  notifications.reset()
  localStorage.removeItem('user')
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(TOKEN_EXPIRY_KEY)
}

const createDocument = async (title) => {
  const response = await api.post('/api/documents', { title })
  return response.data.id
}

restore()

// A 401 from any call means the credential is dead: drop the session and close the socket, otherwise the
// app renders a signed-in shell whose every request fails. Any step batch that had not been acknowledged
// when this fires is lost with the socket — that is unavoidable once the server refuses the identity.
setUnauthorizedHandler(() => {
  signOut()
  wsService.disconnect()
})

export default { currentUser, signIn, signOut, createDocument }

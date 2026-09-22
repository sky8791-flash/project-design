import { createRouter, createWebHashHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import DocumentView from '../views/DocumentView.vue'
import AdminView from '../views/AdminView.vue'
import session from '../services/session'

/**
 * Hash history so a deep link survives a static host without an SPA rewrite rule.
 */
const routes = [
  { path: '/', name: 'home', component: HomeView },
  { path: '/admin', name: 'admin', component: AdminView },
  {
    path: '/doc/:id',
    name: 'document',
    component: DocumentView,
    props: (route) => ({ documentId: String(route.params.id) })
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHashHistory(), routes })

/**
 * Guards here rather than in `App.vue` because a refresh has to be resolved before a view mounts:
 * `/#/doc/5` must survive for a signed-in user, and `/#/admin` must not render for anyone else.
 */
router.beforeEach((to) => {
  const user = session.currentUser.value
  if (!user) return to.path === '/' ? true : { path: '/' }
  if (to.name === 'admin' && user.role !== 'ADMIN') return { name: 'home' }
  return true
})

export default router

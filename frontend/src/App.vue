<template>
  <div id="app">
    <div v-if="!user" class="login-container">
      <LoginView @login="handleLogin" />
    </div>
    <template v-else>
      <NotificationBell :user="user" />
      <div v-if="isDocument" class="editor-container">
        <!-- keyed by route: one document session owns one socket, one collab client and one clientId -->
        <router-view
          :key="$route.fullPath"
          :user="user"
          @back="goHome"
          @logout="handleLogout"
          @go-admin="goAdmin"
        />
      </div>
      <div v-else class="home-container">
        <router-view
          :key="$route.fullPath"
          :user="user"
          @open-document="openDocument"
          @create-document="createDocument"
          @logout="handleLogout"
          @go-admin="goAdmin"
          @back="goHome"
        />
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LoginView from './views/LoginView.vue'
import NotificationBell from './components/NotificationBell.vue'
import session from './services/session'

const user = computed(() => session.currentUser.value)

const route = useRoute()
const router = useRouter()
const isDocument = computed(() => route.name === 'document')

const handleLogin = (signedIn) => session.signIn(signedIn)

const handleLogout = () => {
  session.signOut()
  router.replace({ name: 'home' })
}

const goHome = () => router.push({ name: 'home' })
const goAdmin = () => router.push({ name: 'admin' })
const openDocument = (docId) => router.push({ name: 'document', params: { id: String(docId) } })

const createDocument = async (title) => {
  try {
    openDocument(await session.createDocument(title))
  } catch (error) {
    console.error('Failed to create document:', error)
    alert('创建文档失败，请重试')
  }
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background-color: #f5f5f5;
}

#app {
  min-height: 100vh;
}

.login-container, .home-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
}

.editor-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
</style>

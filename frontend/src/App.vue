<template>
  <div id="app">
    <div v-if="!currentUser" class="login-container">
      <LoginView @login="handleLogin" />
    </div>
    <div v-else-if="adminMode" class="home-container">
      <AdminView :user="currentUser" @back="adminMode = false" />
    </div>
    <div v-else-if="!currentDocument" class="home-container">
      <HomeView :user="currentUser" @open-document="openDocument" @create-document="createDocument" @logout="handleLogout" @go-admin="adminMode = true" />
    </div>
    <div v-else class="editor-container">
      <DocumentView :user="currentUser" :document-id="currentDocument" @back="currentDocument = null" />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import LoginView from './views/LoginView.vue'
import HomeView from './views/HomeView.vue'
import DocumentView from './views/DocumentView.vue'
import AdminView from './views/AdminView.vue'
import api from './services/api'

const stored = localStorage.getItem('user')
const currentUser = ref(stored ? JSON.parse(stored) : null)
const currentDocument = ref(null)
const adminMode = ref(false)

const handleLogin = (user) => {
  currentUser.value = user
  localStorage.setItem('user', JSON.stringify(user))
}

const handleLogout = () => {
  currentUser.value = null
  currentDocument.value = null
  adminMode.value = false
  localStorage.removeItem('user')
}

const openDocument = (docId) => {
  currentDocument.value = docId
}

const createDocument = async (title) => {
  try {
    const response = await api.post('/api/documents', {
      title: title,
      userId: String(currentUser.value.id)
    })
    currentDocument.value = String(response.data.id)
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

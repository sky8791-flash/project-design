<template>
  <div class="notif-bell">
    <button class="bell-btn" @click="open = !open">
      通知<span v-if="store.unreadCount.value" class="badge">{{ store.unreadCount.value }}</span>
    </button>
    <div v-if="open" class="notif-panel">
      <div class="notif-head">
        <span>最新通知</span>
        <button v-if="store.items.value.length" class="notif-mark" @click="markRead">全部已读</button>
        <button class="notif-close" @click="open = false">×</button>
      </div>
      <div v-if="!store.items.value.length" class="notif-empty">暂无通知</div>
      <div
        v-for="item in store.items.value"
        :key="item.id"
        class="notif-item"
        @click="follow(item)"
      >
        <div class="notif-msg">{{ item.message }}</div>
        <div class="notif-time">{{ formatTime(item.createdAt) }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import notifications from '../services/notifications'

const props = defineProps({ user: Object })

const store = notifications
const router = useRouter()
const open = ref(false)

const formatTime = (value) => (value ? new Date(value).toLocaleString('zh-CN') : '')

const markRead = () => notifications.markAllRead()

const follow = async (item) => {
  if (!item.documentId) return
  await notifications.markAllRead()
  open.value = false
  router.push({ name: 'document', params: { id: String(item.documentId) } })
}

watch(() => props.user?.id, (id) => { if (id) notifications.load() }, { immediate: true })
</script>

<style scoped>
.notif-bell {
  position: fixed;
  top: 16px;
  right: 20px;
  z-index: 40;
}

.bell-btn {
  padding: 6px 12px;
  border: 1px solid #ddd;
  border-radius: 16px;
  background: white;
  cursor: pointer;
  font-size: 13px;
}

.badge {
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 8px;
  background: #d9534f;
  color: white;
  font-size: 11px;
}

.notif-panel {
  position: absolute;
  top: 36px;
  right: 0;
  width: 280px;
  max-height: 320px;
  overflow: auto;
  background: white;
  border: 1px solid #eee;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
}

.notif-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 13px;
  color: #666;
}

.notif-mark,
.notif-close {
  border: none;
  background: none;
  cursor: pointer;
  font-size: 12px;
  color: #4a90d9;
}

.notif-close {
  margin-left: auto;
  font-size: 16px;
  color: #999;
}

.notif-empty {
  padding: 16px 12px;
  font-size: 13px;
  color: #999;
}

.notif-item {
  padding: 10px 12px;
  border-bottom: 1px solid #f7f7f7;
  cursor: pointer;
}

.notif-item:hover {
  background: #fafafa;
}

.notif-msg {
  font-size: 13px;
  color: #333;
}

.notif-time {
  margin-top: 4px;
  font-size: 11px;
  color: #aaa;
}
</style>

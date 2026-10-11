import { createApp } from 'vue'
import { createWebHistory } from 'vue-router'
import App from './App.vue'
import { createAppRouter } from './router'
import './style.css'

createApp(App).use(createAppRouter(createWebHistory(import.meta.env.BASE_URL))).mount('#app')

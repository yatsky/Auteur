import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
// 在挂载前先 import useTheme,模块顶层会读 localStorage 把 .light 类加到 html 上,避免首屏闪
import './composables/useTheme'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')

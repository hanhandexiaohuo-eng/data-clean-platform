import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/app.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { appMode, loadAppMode } from './stores/appMode'
import { ensurePublicSession } from './api/publicSession'

const app = createApp(App)

// 注册所有图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

// 先确定运行模式，再挂载，避免公开模式被守卫重定向到登录页
loadAppMode()
  .then(() => (appMode.publicMode ? ensurePublicSession() : null))
  .finally(() => {
    app.mount('#app')
  })

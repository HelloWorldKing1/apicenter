import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

// API 中心管理面入口(原型交互平移:doc/API中心原型.html)
createApp(App)
  .use(ElementPlus, { locale: zhCn })
  .use(router)
  .mount('#app')

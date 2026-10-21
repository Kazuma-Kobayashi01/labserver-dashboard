import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import vuetify from './theme'
import './theme/tokens.css'
import './theme/global.css'

createApp(App).use(router).use(vuetify).mount('#app')

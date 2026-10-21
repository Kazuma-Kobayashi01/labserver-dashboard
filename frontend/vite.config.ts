import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'

// For `pnpm dev`, tunnel the API first:
//   ssh -L 18080:127.0.0.1:18080 user@your-server
export default defineConfig({
  plugins: [vue(), vuetify({ autoImport: true })],
  server: { proxy: { '/api': 'http://127.0.0.1:18080' } },
  build: { chunkSizeWarningLimit: 900 },
})

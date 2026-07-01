import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'http://localhost:8080', ws: true }
    }
  },
  // 前后端分离：前端用 Vite dev server (3000) 独立运行，
  // build 产物输出到 frontend/dist/，不混入 Spring Boot 静态资源目录
  build: {
    outDir: 'dist',
    emptyOutDir: true
  }
})

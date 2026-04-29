import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],  server: {
    host: '0.0.0.0',
    port: 5173,
    // Local dev proxy: forwards /api/* requests to the right Spring Boot service.
    // In production, CloudFront handles this routing instead (no proxy needed).
    proxy: {
      '/api/auth':     { target: 'http://localhost:8080', changeOrigin: true },
      '/api/products': { target: 'http://localhost:8081', changeOrigin: true },
      '/api/categories': { target: 'http://localhost:8081', changeOrigin: true },
      '/api/cart':     { target: 'http://localhost:8082', changeOrigin: true },
      '/api/orders':   { target: 'http://localhost:8083', changeOrigin: true },
    }
  }
})

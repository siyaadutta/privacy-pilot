import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The API base is configurable at build/runtime via VITE_API_BASE.
// In dev, requests to /api are proxied to the Spring Boot backend.
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  preview: {
    host: true,
    port: 5173,
  },
});

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the React app calls the Spring Boot API through this proxy, so the browser only ever talks to
// one origin. Set VITE_API_BASE_URL at build time when the API is hosted elsewhere.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.VITE_BACKEND_URL || 'http://localhost:8080', changeOrigin: true },
    },
  },
});

import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The console talks to the gateway over /api and /ws. Proxying both in dev
// keeps the browser on a single origin, so nothing here depends on CORS being
// configured a particular way -- and a production build served by any static
// host behaves identically as long as the two paths are routed to the gateway.
const GATEWAY = process.env.DKM_GATEWAY ?? 'http://127.0.0.1:8080'

export default defineConfig({
    plugins: [react(), tailwindcss()],
    server: {
        port: 5173,
        proxy: {
            '/api': { target: GATEWAY, changeOrigin: true },
            '/ws': { target: GATEWAY, ws: true, changeOrigin: true },
        },
    },
    build: {
        target: 'es2022',
        sourcemap: true,
    },
})

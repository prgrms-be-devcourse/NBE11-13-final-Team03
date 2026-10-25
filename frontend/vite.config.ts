import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'BACKEND_')

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': {
          target: env.BACKEND_URL || 'http://localhost:8080',
          changeOrigin: true,
          configure(proxy) {
            // 개발 프록시 연결 거부를 백엔드가 반환한 HTTP 오류와 구분한다.
            proxy.on('error', (error, _request, response) => {
              if ('code' in error && error.code === 'ECONNREFUSED' && 'writeHead' in response && !response.headersSent && !response.writableEnded) {
                response.writeHead(502, { 'Content-Type': 'application/json' })
                response.end(JSON.stringify({ code: 'DEV_BACKEND_UNAVAILABLE', message: '개발 백엔드 연결이 거부되었습니다.', fieldErrors: {} }))
              }
            })
          },
        },
      },
    },
  }
})

import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'
import { fileURLToPath } from 'node:url'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'BACKEND_')

  return {
    plugins: [react()],
    build: {
      rolldownOptions: {
        input: {
          main: fileURLToPath(new URL('./index.html', import.meta.url)),
          loginSuccess: fileURLToPath(new URL('./login-success.html', import.meta.url)),
          loginFailure: fileURLToPath(new URL('./login-failure.html', import.meta.url)),
        },
      },
    },
    server: {
      proxy: {
        '/oauth2/authorization': {
          target: env.BACKEND_URL || 'http://localhost:8080', changeOrigin: false, xfwd: true,
        },
        '/login/oauth2/code': {
          target: env.BACKEND_URL || 'http://localhost:8080', changeOrigin: false, xfwd: true,
        },
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

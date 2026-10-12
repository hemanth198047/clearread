/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const apiPort = env.CLEARREAD_API_PORT || process.env.CLEARREAD_API_PORT || "8080";
  const webPort = Number(env.CLEARREAD_WEB_PORT || process.env.CLEARREAD_WEB_PORT || "5173");
  const apiTarget =
    env.VITE_API_BASE_URL || process.env.VITE_API_BASE_URL || `http://localhost:${apiPort}`;

  return {
    plugins: [react()],
    server: {
      port: webPort,
      host: true,
      // Dev convenience: relative /v1 and /actuator hit the API even when the browser
      // origin port differs; SPA still prefers VITE_API_BASE_URL for absolute fetches.
      proxy: {
        "/v1": { target: apiTarget, changeOrigin: true },
        "/actuator": { target: apiTarget, changeOrigin: true },
        "/v3": { target: apiTarget, changeOrigin: true },
        "/swagger-ui": { target: apiTarget, changeOrigin: true },
      },
    },
    test: {
      environment: "jsdom",
      globals: true,
      exclude: ["**/node_modules/**", "**/dist/**", "**/e2e/**"],
    },
  };
});

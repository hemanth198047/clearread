const apiBase = process.env.CLEARREAD_API_URL ?? "http://localhost:8080";
const webBase = process.env.CLEARREAD_WEB_URL ?? "http://localhost:5173";

export async function stackIsReady(): Promise<boolean> {
  try {
    const [health, web] = await Promise.all([
      fetch(`${apiBase}/v1/health`),
      fetch(webBase),
    ]);
    return health.ok && web.ok;
  } catch {
    return false;
  }
}

export function shouldForceE2E(): boolean {
  return process.env.CLEARREAD_E2E_FORCE === "1";
}

export { apiBase, webBase };

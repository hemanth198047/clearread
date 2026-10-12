import type { Page } from "@playwright/test";
import { apiBase } from "./stack";

const authority = process.env.CLEARREAD_OIDC_AUTHORITY ?? "http://localhost:8081/realms/clearread";
const clientId = process.env.CLEARREAD_OIDC_CLIENT_ID ?? "clearread-spa";
const tokenUrl =
  process.env.CLEARREAD_OIDC_TOKEN_URL ??
  `${authority}/protocol/openid-connect/token`;
const username = process.env.CLEARREAD_E2E_USER ?? "demo";
const password = process.env.CLEARREAD_E2E_PASSWORD ?? "demo";

type TokenResponse = {
  access_token: string;
  refresh_token?: string;
  id_token?: string;
  expires_in: number;
  token_type: string;
  scope?: string;
};

export async function fetchDemoTokens(): Promise<TokenResponse> {
  const body = new URLSearchParams({
    grant_type: "password",
    client_id: clientId,
    username,
    password,
    scope: "openid profile email",
  });
  const res = await fetch(tokenUrl, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!res.ok) {
    throw new Error(`Token request failed: ${res.status} ${await res.text()}`);
  }
  return (await res.json()) as TokenResponse;
}

/** Seed oidc-client-ts session so the SPA treats the browser as signed in. */
export async function loginViaPasswordGrant(page: Page): Promise<void> {
  const tokens = await fetchDemoTokens();
  const expiresAt = Math.floor(Date.now() / 1000) + (tokens.expires_in || 300);
  const user = {
    id_token: tokens.id_token,
    session_state: null,
    access_token: tokens.access_token,
    refresh_token: tokens.refresh_token,
    token_type: tokens.token_type,
    scope: tokens.scope ?? "openid profile email",
    profile: { preferred_username: username, sub: username },
    expires_at: expiresAt,
  };
  const key = `oidc.user:${authority}:${clientId}`;
  await page.goto("/");
  await page.evaluate(
    ({ storageKey, value }) => {
      sessionStorage.setItem(storageKey, JSON.stringify(value));
    },
    { storageKey: key, value: user }
  );
  await page.reload();
  // Sanity: API accepts the token
  const probe = await fetch(`${apiBase}/v1/documents`, {
    headers: { Authorization: `Bearer ${tokens.access_token}` },
  });
  if (!probe.ok) {
    throw new Error(`Authenticated probe failed: ${probe.status}`);
  }
}

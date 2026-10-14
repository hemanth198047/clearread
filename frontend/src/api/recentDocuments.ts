import { apiFetch, formatApiError } from "./client";

export type RecentDocMeta = {
  id: string;
  title: string;
  status: string;
  formatId: string;
  jobId?: string;
  duplicateOfExisting?: boolean;
  restoredStorage?: boolean;
  originalMissing?: boolean;
  cleardocMissing?: boolean;
  storageOrphaned?: boolean;
  recovery?: string;
  message?: string;
};

type CacheEntry = {
  tokenKey: string;
  at: number;
  docs: RecentDocMeta[];
};

const TTL_MS = 30_000;
const BACKOFF_BASE_MS = 15_000;
const BACKOFF_MAX_MS = 120_000;

let cache: CacheEntry | null = null;
let inflight: Promise<RecentDocMeta[]> | null = null;
let backoffUntil = 0;
let backoffMs = BACKOFF_BASE_MS;

export function clearRecentDocumentsCacheForTests(): void {
  cache = null;
  inflight = null;
  backoffUntil = 0;
  backoffMs = BACKOFF_BASE_MS;
}

function isRateLimitError(err: unknown): boolean {
  const msg = err instanceof Error ? err.message : String(err);
  return /too many requests|rate[_ ]?limit|429/i.test(msg);
}

/**
 * Fetch recent workspace documents with in-flight dedupe, short TTL cache,
 * and exponential backoff after 429 so Home remounts / StrictMode do not storm the API.
 */
export async function fetchRecentDocuments(
  token: string
): Promise<{ docs: RecentDocMeta[]; fromCache: boolean; error: string | null }> {
  const now = Date.now();
  const tokenKey = token.slice(0, 24);

  if (cache && cache.tokenKey === tokenKey && now - cache.at < TTL_MS) {
    return { docs: cache.docs, fromCache: true, error: null };
  }

  if (now < backoffUntil) {
    if (cache && cache.tokenKey === tokenKey) {
      return {
        docs: cache.docs,
        fromCache: true,
        error: "Recent documents: Too many requests — showing cached list. Retry shortly.",
      };
    }
    return {
      docs: [],
      fromCache: false,
      error: "Recent documents: Too many requests — backing off before retry.",
    };
  }

  if (inflight) {
    try {
      const docs = await inflight;
      return { docs, fromCache: false, error: null };
    } catch (e) {
      if (cache && cache.tokenKey === tokenKey) {
        return {
          docs: cache.docs,
          fromCache: true,
          error: formatApiError(e, "Recent documents"),
        };
      }
      return { docs: [], fromCache: false, error: formatApiError(e, "Recent documents") };
    }
  }

  inflight = (async () => {
    const data = (await apiFetch("/v1/documents", token)) as RecentDocMeta[];
    cache = { tokenKey, at: Date.now(), docs: data };
    backoffMs = BACKOFF_BASE_MS;
    backoffUntil = 0;
    return data;
  })();

  try {
    const docs = await inflight;
    return { docs, fromCache: false, error: null };
  } catch (e) {
    if (isRateLimitError(e)) {
      backoffUntil = Date.now() + backoffMs;
      backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
      if (cache && cache.tokenKey === tokenKey) {
        return {
          docs: cache.docs,
          fromCache: true,
          error: "Recent documents: Too many requests — showing cached list. Retry shortly.",
        };
      }
      return {
        docs: [],
        fromCache: false,
        error: "Recent documents: Too many requests — will retry after a short wait.",
      };
    }
    if (cache && cache.tokenKey === tokenKey) {
      return {
        docs: cache.docs,
        fromCache: true,
        error: formatApiError(e, "Recent documents"),
      };
    }
    return { docs: [], fromCache: false, error: formatApiError(e, "Recent documents") };
  } finally {
    inflight = null;
  }
}

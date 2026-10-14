import { createContext, useContext, type ReactNode } from "react";
import { useLocalLibrary, type LocalLibraryApi } from "./useLocalLibrary";

const LocalLibraryContext = createContext<LocalLibraryApi | null>(null);

/**
 * Single shared local-library state for Settings / Library / Ingest so scans
 * and IndexedDB lists stay in sync, and startup auto-scan runs once.
 */
export function LocalLibraryProvider({ children }: { children: ReactNode }) {
  const value = useLocalLibrary(true);
  return <LocalLibraryContext.Provider value={value}>{children}</LocalLibraryContext.Provider>;
}

export function useSharedLocalLibrary(): LocalLibraryApi {
  const ctx = useContext(LocalLibraryContext);
  if (!ctx) {
    throw new Error("useSharedLocalLibrary must be used within LocalLibraryProvider");
  }
  return ctx;
}

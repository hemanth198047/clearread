import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { apiBase, formatApiError } from "../api/client";

export type Branding = {
  productName: string;
  primaryColor: string;
  secondaryColor: string;
  logoUrl: string;
  /** Local/demo stack only — hide in production via CLEARREAD_SHOW_DEMO_ACCOUNTS=false */
  showDemoAccounts: boolean;
};

export const DEMO_ACCOUNTS = [
  { username: "admin", password: "admin", role: "Workspace admin" },
  { username: "user", password: "user", role: "Standard member (author)" },
  { username: "demo", password: "demo", role: "Legacy admin (compat)" },
] as const;

const defaultBranding: Branding = {
  productName: "ClearRead",
  primaryColor: "#1a5f4a",
  secondaryColor: "#c4a35a",
  logoUrl: "",
  showDemoAccounts: true,
};

const BrandingContext = createContext<Branding>(defaultBranding);

export function BrandingProvider({ children }: { children: ReactNode }) {
  const [branding, setBranding] = useState<Branding>(defaultBranding);

  useEffect(() => {
    const url = `${apiBase}/v1/branding/default`;
    fetch(url)
      .then(async (r) => {
        if (!r.ok) throw new Error(r.statusText || `HTTP ${r.status}`);
        return r.json();
      })
      .then((data: Partial<Branding>) => {
        const next = { ...defaultBranding, ...data };
        setBranding(next);
        document.documentElement.style.setProperty("--color-primary", next.primaryColor || defaultBranding.primaryColor);
        document.documentElement.style.setProperty(
          "--color-secondary",
          next.secondaryColor || defaultBranding.secondaryColor
        );
        document.title = next.productName || defaultBranding.productName;
      })
      .catch((err) => {
        // Keep defaults offline; log actionable message for operators.
        console.warn(formatApiError(err, "Branding"));
      });
  }, []);

  return <BrandingContext.Provider value={branding}>{children}</BrandingContext.Provider>;
}

export function useBranding() {
  return useContext(BrandingContext);
}

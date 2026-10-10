import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { apiBase } from "../api/client";

export type Branding = {
  productName: string;
  primaryColor: string;
  secondaryColor: string;
  logoUrl: string;
};

const defaultBranding: Branding = {
  productName: "ClearRead",
  primaryColor: "#1a5f4a",
  secondaryColor: "#c4a35a",
  logoUrl: "",
};

const BrandingContext = createContext<Branding>(defaultBranding);

export function BrandingProvider({ children }: { children: ReactNode }) {
  const [branding, setBranding] = useState<Branding>(defaultBranding);

  useEffect(() => {
    fetch(`${apiBase}/v1/branding/default`)
      .then((r) => r.json())
      .then((data: Branding) => {
        setBranding({ ...defaultBranding, ...data });
        document.documentElement.style.setProperty("--color-primary", data.primaryColor || defaultBranding.primaryColor);
        document.documentElement.style.setProperty("--color-secondary", data.secondaryColor || defaultBranding.secondaryColor);
        document.title = data.productName || defaultBranding.productName;
      })
      .catch(() => {
        /* keep defaults offline */
      });
  }, []);

  return <BrandingContext.Provider value={branding}>{children}</BrandingContext.Provider>;
}

export function useBranding() {
  return useContext(BrandingContext);
}

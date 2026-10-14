import { Link } from "react-router-dom";
import type { WorkspaceAiStatus } from "./useWorkspaceAiStatus";

type Variant = "topbar" | "settings" | "home";

type Props = {
  status: WorkspaceAiStatus;
  variant?: Variant;
};

/**
 * Visible “AI enabled” cue for main app surfaces (not Admin-only).
 */
export function AiStatusChrome({ status, variant = "topbar" }: Props) {
  if (status.loading && variant === "topbar") {
    return null;
  }

  if (!status.aiEnabled) {
    if (variant === "settings") {
      return (
        <div className="ai-status-panel" data-testid="ai-status-chrome" data-state="off">
          <div className="status-bar">
            <span className="status-dot err" />
            <span>AI enrichment is off for this workspace</span>
          </div>
          <p className="muted">
            Operators enable policy and connection under Admin. Core reading never requires AI.
          </p>
          <p>
            <Link className="button secondary" to={status.configurePath}>
              Open Admin AI settings
            </Link>
          </p>
        </div>
      );
    }
    return null;
  }

  const tone = status.connected ? "on" : "warn";
  const label = status.connected ? "AI enabled" : "AI on · provider missing";
  const title = `${label} · ${status.detail}`;

  if (variant === "topbar") {
    return (
      <Link
        to="/settings"
        className={`ai-status-chip ai-status-chip--${tone}`}
        data-testid="ai-status-chrome"
        data-state={tone}
        title={title}
        aria-label={title}
      >
        <span className={`status-dot${status.connected ? "" : " err"}`} />
        <span className="ai-status-chip-label">{label}</span>
        <span className="ai-status-chip-meta muted">{status.providerLabel}</span>
      </Link>
    );
  }

  if (variant === "home") {
    return (
      <div className={`ai-status-inline ai-status-inline--${tone}`} data-testid="ai-status-chrome" data-state={tone}>
        <span className={`status-dot${status.connected ? "" : " err"}`} />
        <span>
          <strong>{label}</strong>
          <span className="muted"> · {status.detail}</span>
        </span>
        <Link className="button secondary compact" to="/settings">
          AI status
        </Link>
      </div>
    );
  }

  return (
    <div className="ai-status-panel" data-testid="ai-status-chrome" data-state={tone}>
      <div className="status-bar">
        <span className={`status-dot${status.connected ? "" : " err"}`} />
        <span>
          {label} · {status.providerLabel}
        </span>
        <span className="muted">{status.detail}</span>
      </div>
      <p className="muted">
        Enrichment uses workspace policy and the selected provider. Derived artifacts only — source ClearDoc is never
        silently mutated.
      </p>
      <p>
        <Link className="button secondary" to={status.configurePath}>
          Configure in Admin
        </Link>
      </p>
    </div>
  );
}

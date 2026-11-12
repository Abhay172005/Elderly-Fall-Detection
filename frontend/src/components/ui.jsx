import { useEffect, useId, useRef, useState } from 'react';
import { AlertTriangle, CheckCircle2, ChevronLeft, ChevronRight, CircleDashed, CircleSlash, ImageOff, Loader2, RefreshCw, X } from 'lucide-react';
import { api } from '../api/client';
import { EMAIL_LABELS, STATUS_LABELS } from '../utils/format';

export function Logo({ light = false, compact = false }) {
  return (
    <span className={`brand ${light ? 'light' : ''}`}>
      <span className="logo" aria-hidden>
        <svg viewBox="0 0 32 32" fill="none">
          <path d="M16 3.5 26 7.4v7.2c0 6.6-4.2 11.7-10 14-5.8-2.3-10-7.4-10-14V7.4L16 3.5Z" fill="currentColor" opacity=".18" />
          <path d="M16 4.5 25 8v6.6c0 5.8-3.6 10.4-9 12.7-5.4-2.3-9-6.9-9-12.7V8l9-3.5Z" stroke="currentColor" strokeWidth="2.2" strokeLinejoin="round" />
          <path d="M9.5 16h3l1.6-3.2 3.1 6.3 1.5-3h3.8" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </span>
      {!compact && <span className="brandText">Smart Elderly<b>Fall Detection Portal</b></span>}
    </span>
  );
}

const STATUS_ICONS = { VERIFIED: AlertTriangle, PENDING: CircleDashed, RESOLVED: CheckCircle2, FALSE_ALARM: CircleSlash };

export function StatusBadge({ status }) {
  const Icon = STATUS_ICONS[status] || CircleDashed;
  return (
    <span className={`badge status-${(status || 'PENDING').toLowerCase()}`}>
      <Icon aria-hidden /> {STATUS_LABELS[status] || status}
    </span>
  );
}

export function EmailBadge({ status }) {
  const tone = { SENT: 'ok', FAILED: 'bad', NOT_CONFIGURED: 'muted', DISABLED: 'muted', SKIPPED: 'muted', PENDING: 'warn' }[status] || 'muted';
  return <span className={`pill ${tone}`}>{EMAIL_LABELS[status] || status}</span>;
}

export function Spinner({ label = 'Loading…' }) {
  return <span className="spinner" role="status"><Loader2 aria-hidden className="spin" /> {label}</span>;
}

export function LoadingBlock({ label = 'Loading…' }) {
  return <div className="stateBlock"><Spinner label={label} /></div>;
}

export function EmptyState({ icon: Icon = CircleDashed, title, children, action }) {
  return (
    <div className="stateBlock">
      <span className="stateIcon"><Icon aria-hidden /></span>
      <b>{title}</b>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function ErrorState({ message, onRetry }) {
  return (
    <div className="stateBlock error" role="alert">
      <span className="stateIcon"><AlertTriangle aria-hidden /></span>
      <b>Could not load data</b>
      <p>{message}</p>
      {onRetry && <button type="button" className="btn outline" onClick={onRetry}><RefreshCw aria-hidden /> Try again</button>}
    </div>
  );
}

export function Alert({ tone = 'info', title, children, action }) {
  return (
    <div className={`alert ${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <div className="alertBody">{title && <b>{title}</b>}{children && <div>{children}</div>}</div>
      {action}
    </div>
  );
}

export function PageHeader({ eyebrow, title, description, actions }) {
  return (
    <div className="pageHeader">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {description && <p className="muted">{description}</p>}
      </div>
      {actions && <div className="pageActions">{actions}</div>}
    </div>
  );
}

export function Modal({ title, children, onClose, footer, width = 480 }) {
  const titleId = useId();
  const ref = useRef(null);
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    ref.current?.querySelector('textarea, input, select, button:not(.iconBtn)')?.focus();
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modalBackdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={titleId} style={{ maxWidth: width }} ref={ref}>
        <div className="modalHead">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="iconBtn" onClick={onClose} aria-label="Close"><X /></button>
        </div>
        <div className="modalBody">{children}</div>
        {footer && <div className="modalFoot">{footer}</div>}
      </div>
    </div>
  );
}

export function ConfirmDialog({ title, message, confirmLabel = 'Confirm', danger = false, busy = false, error, onConfirm, onCancel }) {
  return (
    <Modal title={title} onClose={busy ? () => {} : onCancel} footer={(
      <>
        <button type="button" className="btn outline" onClick={onCancel} disabled={busy}>Cancel</button>
        <button type="button" className={`btn ${danger ? 'danger' : 'primary'}`} onClick={onConfirm} disabled={busy}>
          {busy ? <Loader2 className="spin" aria-hidden /> : null} {confirmLabel}
        </button>
      </>
    )}>
      <p>{message}</p>
      {error && <p className="formError">{error}</p>}
    </Modal>
  );
}

export function Pagination({ page, totalPages, totalElements, size, onPage }) {
  if (!totalElements) return null;
  const from = page * size + 1;
  const to = Math.min(totalElements, (page + 1) * size);
  return (
    <div className="pagination">
      <span className="muted">Showing {from}–{to} of {totalElements}</span>
      <div>
        <button type="button" className="btn outline small" disabled={page <= 0} onClick={() => onPage(page - 1)}><ChevronLeft aria-hidden /> Previous</button>
        <span className="pageNo">Page {page + 1} of {Math.max(1, totalPages)}</span>
        <button type="button" className="btn outline small" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next <ChevronRight aria-hidden /></button>
      </div>
    </div>
  );
}

/** Loads an image from an authenticated endpoint (img src cannot carry the bearer token). */
export function AuthImage({ src, alt, className, fallback = 'No image available', version }) {
  const [state, setState] = useState({ url: null, error: false, loading: true });
  useEffect(() => {
    if (!src) {
      setState({ url: null, error: true, loading: false });
      return undefined;
    }
    let url = null;
    let active = true;
    setState({ url: null, error: false, loading: true });
    api.get(src, { responseType: 'blob' })
      .then(({ data }) => {
        url = URL.createObjectURL(data);
        if (active) setState({ url, error: false, loading: false });
      })
      .catch(() => active && setState({ url: null, error: true, loading: false }));
    return () => {
      active = false;
      if (url) URL.revokeObjectURL(url);
    };
  }, [src, version]);
  if (state.loading) return <div className={`imagePlaceholder ${className || ''}`}><Spinner label="Loading image…" /></div>;
  if (state.error) return <div className={`imagePlaceholder ${className || ''}`}><ImageOff aria-hidden /><span>{fallback}</span></div>;
  return <img src={state.url} alt={alt} className={className} />;
}

export function Field({ label, error, hint, children, htmlFor }) {
  return (
    <div className={`field ${error ? 'invalid' : ''}`}>
      <label htmlFor={htmlFor}>{label}</label>
      {children}
      {error ? <span className="fieldError">{error}</span> : hint ? <span className="hint">{hint}</span> : null}
    </div>
  );
}

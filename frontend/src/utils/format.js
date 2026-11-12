const dateTime = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
const dateTimeLong = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'medium' });
const dayShort = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short' });

export const fmtDateTime = (v) => (v ? dateTime.format(new Date(v)) : '—');
export const fmtDateTimeLong = (v) => (v ? dateTimeLong.format(new Date(v)) : '—');
export const fmtDay = (v) => (v ? dayShort.format(new Date(`${v}T00:00:00`)) : '');
export const fmtPercent = (v) => (v === null || v === undefined ? '—' : `${Math.round(v * 100)}%`);
export const fmtSeconds = (v) => (v === null || v === undefined ? '—' : `${Number(v).toFixed(1)} s`);

export function fmtBytes(bytes) {
  if (bytes === null || bytes === undefined) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function fmtDuration(seconds) {
  if (!Number.isFinite(seconds)) return '—';
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return `${m}:${String(s).padStart(2, '0')}`;
}

export function timeAgo(v) {
  if (!v) return '';
  const diff = (Date.now() - new Date(v).getTime()) / 1000;
  if (diff < 60) return 'just now';
  if (diff < 3600) return `${Math.floor(diff / 60)} min ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`;
  return `${Math.floor(diff / 86400)} d ago`;
}

export const STATUS_LABELS = {
  VERIFIED: 'Verified fall',
  PENDING: 'Pending review',
  RESOLVED: 'Resolved',
  FALSE_ALARM: 'False alarm',
};

export const EMAIL_LABELS = {
  PENDING: 'Pending',
  SENT: 'Sent',
  FAILED: 'Failed',
  NOT_CONFIGURED: 'Not configured',
  DISABLED: 'Disabled',
  SKIPPED: 'Not sent',
};

export const POSTURE_LABELS = {
  STANDING: 'Standing',
  WALKING: 'Walking',
  SITTING: 'Sitting',
  BENDING: 'Bending',
  LYING: 'Lying down',
  ON_FLOOR: 'On the floor',
  UNKNOWN: 'Unclear',
};

export function greeting(date = new Date()) {
  const h = date.getHours();
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  return 'Good evening';
}

import axios from 'axios';

const SESSION_KEY = 'sefd.session';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30000,
});

export function loadSession() {
  try {
    const session = JSON.parse(localStorage.getItem(SESSION_KEY));
    if (!session?.token || !session?.expiresAt) return null;
    if (new Date(session.expiresAt).getTime() <= Date.now()) {
      localStorage.removeItem(SESSION_KEY);
      return null;
    }
    return session;
  } catch {
    return null;
  }
}

export function saveSession(session) {
  if (session) localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  else localStorage.removeItem(SESSION_KEY);
}

let onUnauthorized = () => {};
export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler;
}

api.interceptors.request.use((config) => {
  const session = loadSession();
  if (session) config.headers.Authorization = `Bearer ${session.token}`;
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    const url = error.config?.url || '';
    if (error.response?.status === 401 && !url.startsWith('/auth/login') && !url.startsWith('/auth/register')) {
      onUnauthorized();
    }
    return Promise.reject(error);
  },
);

/** Human-readable message for any API error. Blob error bodies (downloads) are decoded too. */
export async function errorMessage(error, fallback = 'Something went wrong. Please try again.') {
  if (axios.isCancel(error)) return 'Request cancelled.';
  const data = error?.response?.data;
  if (data instanceof Blob) {
    try {
      const parsed = JSON.parse(await data.text());
      return parsed.message || fallback;
    } catch {
      return fallback;
    }
  }
  if (data?.message) return data.message;
  if (typeof data?.detail === 'string') return data.detail;
  if (error?.code === 'ECONNABORTED') return 'The server took too long to respond.';
  if (!error?.response) return 'Cannot reach the server. Check that the backend is running.';
  return fallback;
}

/** Synchronous variant for JSON errors (most cases). */
export function errorText(error, fallback = 'Something went wrong. Please try again.') {
  const data = error?.response?.data;
  if (data?.message) return data.message;
  if (typeof data?.detail === 'string') return data.detail;
  if (error?.response?.status === 403) return 'You do not have permission to perform this action.';
  if (!error?.response) return 'Cannot reach the server. Check that the backend is running.';
  return fallback;
}

export function fieldErrors(error) {
  return error?.response?.data?.fieldErrors || {};
}

/** Downloads an authenticated binary endpoint and saves it with the server-provided filename. */
export async function downloadFile(url, fallbackName, params) {
  const response = await api.get(url, { responseType: 'blob', params, timeout: 120000 });
  const disposition = response.headers['content-disposition'] || '';
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
  const name = match ? decodeURIComponent(match[1]) : fallbackName;
  const href = URL.createObjectURL(response.data);
  const link = document.createElement('a');
  link.href = href;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(href), 1000);
}

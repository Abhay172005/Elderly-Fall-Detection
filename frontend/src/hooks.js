import { useCallback, useEffect, useRef, useState } from 'react';
import { api, errorText } from './api/client';

/** GET an endpoint with loading/error state and optional polling. */
export function useApi(url, { params, interval, enabled = true } = {}) {
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(Boolean(enabled));
  const key = JSON.stringify(params || {});
  const mounted = useRef(true);

  const load = useCallback(async (silent = false) => {
    if (!enabled || !url) return;
    if (!silent) setLoading(true);
    try {
      const response = await api.get(url, { params: JSON.parse(key) });
      if (mounted.current) {
        setData(response.data);
        setError('');
      }
    } catch (e) {
      if (mounted.current && (!silent || !data)) setError(errorText(e));
    } finally {
      if (mounted.current) setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, key, enabled]);

  useEffect(() => {
    mounted.current = true;
    load();
    if (!interval) return () => { mounted.current = false; };
    const timer = setInterval(() => load(true), interval);
    return () => {
      mounted.current = false;
      clearInterval(timer);
    };
  }, [load, interval]);

  return { data, error, loading, reload: load, setData };
}

/** Debounced copy of a value (search boxes). */
export function useDebounced(value, delay = 350) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return v;
}

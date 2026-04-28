import { useEffect, useState } from 'react';
import { progressBus } from '../lib/apiClient';

/**
 * Returns true when at least one API request is currently in-flight.
 * Uses the module-level progressBus so any apiFetch call — from any component —
 * is automatically tracked without passing state props around.
 *
 * Usage:
 *   const loading = useApiProgress();
 *   if (loading) return <Spinner />;
 */
export function useApiProgress(): boolean {
  const [active, setActive] = useState(false);
  useEffect(() => {
    const unsub = progressBus.subscribe(setActive);
    return () => { unsub(); };
  }, []);
  return active;
}

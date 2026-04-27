// Tiny pub/sub for backend-up/down signals. Subscribed to by HealthIndicator
// and any component that wants to know the API status. Mutators are called
// from apiClient on success/failure so the UI reacts within a single request
// cycle, before the 15s background poll would notice.

export type HealthStatus = 'up' | 'down' | 'unknown';

export interface HealthState {
  status: HealthStatus;
  /** Wall-clock when status last flipped. Used by HealthIndicator to debounce
   *  the banner ("show only if down for >10s"). */
  changedAt: number;
  lastError?: string;
}

type Listener = (state: HealthState) => void;

const listeners = new Set<Listener>();
let state: HealthState = { status: 'unknown', changedAt: Date.now() };

function emit() {
  for (const l of listeners) l(state);
}

export const healthBus = {
  get(): HealthState {
    return state;
  },
  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    listener(state);
    return () => listeners.delete(listener);
  },
  markUp(): void {
    if (state.status === 'up') return;
    state = { status: 'up', changedAt: Date.now() };
    emit();
  },
  markDown(error?: string): void {
    if (state.status === 'down' && state.lastError === error) return;
    state = { status: 'down', changedAt: Date.now(), lastError: error };
    emit();
  },
};

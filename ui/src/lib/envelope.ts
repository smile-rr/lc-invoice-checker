import type { FieldEnvelope } from '../types';

/**
 * Read a value from {@link FieldEnvelope.fields}. Backend coerces per the
 * field-pool type — strings come as strings, dates as ISO strings, amounts
 * as numbers/BigDecimal-strings, integers as numbers, booleans as booleans.
 *
 * Returns null for missing keys so call sites can use `??` chaining.
 */
export function field<T = unknown>(env: FieldEnvelope | null | undefined, key: string): T | null {
  if (!env || !env.fields) return null;
  const v = env.fields[key];
  return v === undefined ? null : (v as T);
}

export function strField(env: FieldEnvelope | null | undefined, key: string): string | null {
  const v = field(env, key);
  if (v === null || v === undefined) return null;
  if (typeof v === 'string') return v.trim() === '' ? null : v;
  return String(v);
}

export function numField(env: FieldEnvelope | null | undefined, key: string): number | string | null {
  const v = field(env, key);
  if (v === null || v === undefined) return null;
  if (typeof v === 'number' || typeof v === 'string') return v;
  return null;
}

export function intField(env: FieldEnvelope | null | undefined, key: string, fallback = 0): number {
  const v = field(env, key);
  if (typeof v === 'number') return v;
  if (typeof v === 'string') {
    const n = parseInt(v, 10);
    return Number.isNaN(n) ? fallback : n;
  }
  return fallback;
}

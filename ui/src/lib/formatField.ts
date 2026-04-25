import type { FieldDefinition, FieldType } from '../types';

/**
 * Format a value drawn from FieldEnvelope.fields according to its registered
 * FieldType. Returns null when nothing meaningful can be displayed (caller
 * renders a placeholder dash).
 */
export function formatFieldValue(value: unknown, type: FieldType): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string' && value.trim() === '') return null;

  switch (type) {
    case 'AMOUNT':
      return fmtAmount(value);
    case 'INTEGER':
      return String(value);
    case 'DATE':
      return String(value); // ISO YYYY-MM-DD already from Jackson
    case 'DOCUMENT_LIST':
      if (Array.isArray(value)) return `${value.length} item${value.length === 1 ? '' : 's'}`;
      return null;
    case 'MULTILINE_TEXT':
      return String(value).split(/\r?\n/).filter(Boolean).join(' / ');
    case 'STRING':
    case 'ENUM':
    case 'CURRENCY_CODE':
    default:
      return String(value);
  }
}

function fmtAmount(v: unknown): string {
  if (typeof v === 'number') return v.toLocaleString('en-US', { minimumFractionDigits: 2 });
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString('en-US', { minimumFractionDigits: 2 });
}

/** First source tag (used to render the `:NN:` chip in sidebars). */
export function primaryTag(field: FieldDefinition): string | null {
  return field.source_tags.length > 0 ? field.source_tags[0] : null;
}

/** Display name with a sensible fallback chain. */
export function fieldLabel(field: FieldDefinition): string {
  return field.name_en ?? field.key;
}

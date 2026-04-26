import type { Config } from 'tailwindcss';

// Design tokens aligned with Apple Human Interface Guidelines:
// – Primary label   #1d1d1f  (warm near-black, replaces cold blue-black)
// – Secondary label #6e6e73  (5.74:1 on white → WCAG AA pass; was #8899aa ~3.2:1 fail)
// – Grouped bg      #f5f5f7  (Apple system grouped background)
// – Separator       #d1d1d6  (Apple separator, more visible than previous)
// – Status colors tuned for text-on-background contrast (all ≥4.5:1)
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // navy-1 shifted to Apple primary label: warmer, neutral near-black
        navy: { 1: '#1d1d1f', 2: '#142236', 3: '#1c3050' },
        teal: { 1: '#0a7e6a', 2: '#0e9e86' },
        status: {
          // All foreground values pass WCAG AA (≥4.5:1) for normal text on both
          // white and their paired soft background.
          red:       '#cc0011',  // 5.9:1 on white
          redSoft:   '#fff1f0',
          green:     '#1a7a43',  // 6.5:1 on white
          greenSoft: '#f0fdf4',
          gold:      '#8a5700',  // 5.7:1 on white, 5.5:1 on goldSoft
          goldSoft:  '#fefce8',
          blue:      '#0066cc',  // 5.3:1 on white
          blueSoft:  '#eff6ff',
        },
        slate2: '#f5f5f7',  // Apple system grouped background (was #f4f6fa)
        muted:  '#6e6e73',  // Apple secondary label — WCAG AA (was #8899aa, failed)
        paper:  '#ffffff',
        line:   '#d1d1d6',  // Apple separator (was #e6eaf0, too faint)
      },
      fontFamily: {
        serif: ['"DM Serif Display"', 'Georgia', 'serif'],
        // System fonts first → SF Pro on Apple devices, DM Sans on others
        sans: [
          '-apple-system', 'BlinkMacSystemFont', '"SF Pro Text"', '"SF Pro Display"',
          '"DM Sans"', 'system-ui', 'sans-serif',
        ],
        mono: ['"SF Mono"', '"DM Mono"', 'ui-monospace', 'Menlo', 'monospace'],
      },
      // Named tracking tokens matching Apple HIG optical sizing guidance
      letterSpacing: {
        display: '-0.04em',  // large title / hero text
        heading: '-0.02em',  // section headings
        body:    '-0.01em',  // body / callout
        label:   '0.04em',   // small supporting labels
        caps:    '0.08em',   // ALL CAPS micro-labels
      },
      // HIG-aligned line-height scale (body: 1.5, tight headings: 1.2)
      lineHeight: {
        tight:   '1.2',
        snug:    '1.35',
        normal:  '1.5',
        relaxed: '1.6',
      },
      borderRadius: {
        card:  '10px',
        btn:   '8px',
        input: '6px',
      },
      keyframes: {
        blink: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.35' },
        },
        // Flash keyframe updated to match new gold token (#9a6200 → rgb 255,185,0 highlight)
        flash: {
          '0%':   { backgroundColor: 'rgba(255,185,0,0.30)' },
          '100%': { backgroundColor: 'transparent' },
        },
        fadein: {
          '0%':   { opacity: '0', transform: 'translateY(4px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
      },
      animation: {
        blink:   'blink 1.2s ease-in-out infinite',
        flash:   'flash 1s ease-out',
        fadein:  'fadein 0.2s ease-out',
      },
    },
  },
  plugins: [],
} satisfies Config;

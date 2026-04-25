import type { Config } from 'tailwindcss';

// Design tokens mirrored from refer-doc/lc_checker_v2.html :root vars.
// Keep these in sync — this is the visual contract with the mockup.
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        navy: { 1: '#0d1b2e', 2: '#142236', 3: '#1c3050' },
        teal: { 1: '#0a7e6a', 2: '#0e9e86' },
        status: {
          red: '#e74c3c',
          redSoft: '#fdecea',
          green: '#22a06b',
          greenSoft: '#e6f6ef',
          gold: '#c9933a',
          goldSoft: '#fcf3df',
          blue: '#2563eb',
          blueSoft: '#e6efff',
        },
        slate2: '#f4f6fa',
        muted: '#8899aa',
        paper: '#ffffff',
        line: '#e6eaf0',
      },
      fontFamily: {
        serif: ['"DM Serif Display"', 'Georgia', 'serif'],
        sans: ['"DM Sans"', 'system-ui', 'sans-serif'],
        mono: ['"DM Mono"', 'ui-monospace', 'monospace'],
      },
      borderRadius: {
        card: '10px',
        btn: '8px',
        input: '6px',
      },
      keyframes: {
        blink: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.35' },
        },
        flash: {
          '0%': { backgroundColor: 'rgba(201,147,58,0.35)' },
          '100%': { backgroundColor: 'transparent' },
        },
        fadein: {
          '0%': { opacity: '0', transform: 'translateY(4px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
      },
      animation: {
        blink: 'blink 1.2s ease-in-out infinite',
        flash: 'flash 1s ease-out',
        fadein: 'fadein 0.2s ease-out',
      },
    },
  },
  plugins: [],
} satisfies Config;

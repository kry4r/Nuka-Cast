export default {
  plugins: {
    // Tailwind 4 ships its own PostCSS plugin and handles vendor prefixing itself,
    // so autoprefixer is no longer part of the chain.
    "@tailwindcss/postcss": {},
  },
}

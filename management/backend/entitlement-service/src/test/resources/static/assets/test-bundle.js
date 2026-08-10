// Test fixture standing in for the hashed Vite bundle. Its only job is to be a real
// file under /assets so WebConfigTest can prove the SPA fallback serves it as itself
// rather than swallowing it and returning index.html as text/html.
export const BUNDLE_MARKER = "REAL_BUNDLE_NOT_THE_SPA_SHELL";

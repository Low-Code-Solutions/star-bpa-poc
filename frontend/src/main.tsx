import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from '@/App';
// Manrope (headings) and Nunito Sans (text), self-hosted (no CDN): the portal's two typefaces, the ui-design templates' (decision of Ahsan, 27 Nov 2026; ADR 0017).
import '@fontsource/manrope/500.css';
import '@fontsource/manrope/600.css';
import '@fontsource/manrope/700.css';
import '@fontsource/manrope/800.css';
import '@fontsource/nunito-sans/400.css';
import '@fontsource/nunito-sans/500.css';
import '@fontsource/nunito-sans/600.css';
import '@fontsource/nunito-sans/700.css';
import '@/styles/global.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

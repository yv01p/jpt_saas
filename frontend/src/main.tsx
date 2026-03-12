import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './App.tsx';
import { bootstrapCsrf, hydrateSession } from './api/client';

async function init() {
  try {
    await bootstrapCsrf();
  } catch {
    document.getElementById('root')!.innerHTML =
      '<p style="padding:2rem">Unable to connect to the server. Please refresh the page.</p>';
    return;
  }

  await hydrateSession();

  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  );
}

init();

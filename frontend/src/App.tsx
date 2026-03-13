import { BrowserRouter, Routes, Route, Navigate, Link, useParams } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from './api/client';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import LibraryPage from './pages/LibraryPage';
import PhotoPage from './pages/PhotoPage';
import KeywordsPage from './pages/KeywordsPage';
import AlbumsPage from './pages/AlbumsPage';
import SearchPage from './pages/SearchPage';
import TrashPage from './pages/TrashPage';
import SettingsPage from './pages/SettingsPage';
import SharePage from './pages/SharePage';

function PhotoPageRoute() {
  const { id } = useParams<{ id: string }>();
  return <PhotoPage photoId={id ?? ''} />;
}

function NotFoundPage() {
  return (
    <div style={{ padding: '2rem', textAlign: 'center' }}>
      <h1>Page not found</h1>
      <Link to="/library">Back to library</Link>
    </div>
  );
}

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login"    element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/share/:token" element={<SharePage />} />
          <Route path="/library"  element={<ProtectedRoute><LibraryPage /></ProtectedRoute>} />
          <Route path="/photo/:id" element={<ProtectedRoute><PhotoPageRoute /></ProtectedRoute>} />
          <Route path="/keywords" element={<ProtectedRoute><KeywordsPage /></ProtectedRoute>} />
          <Route path="/albums"   element={<ProtectedRoute><AlbumsPage /></ProtectedRoute>} />
          <Route path="/search"   element={<ProtectedRoute><SearchPage /></ProtectedRoute>} />
          <Route path="/trash"    element={<ProtectedRoute><TrashPage /></ProtectedRoute>} />
          <Route path="/settings" element={<ProtectedRoute><SettingsPage /></ProtectedRoute>} />
          <Route path="/"         element={<Navigate to="/library" replace />} />
          <Route path="*"         element={<NotFoundPage />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}

export default App;

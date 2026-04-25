import { Route, Routes, useParams } from 'react-router-dom';
import { TopNav } from './components/shell/TopNav';
import { UploadPage } from './pages/UploadPage';
import { SessionPage } from './pages/SessionPage';

/**
 * Wrap SessionPage so React unmounts and remounts when the {@code :id} param
 * changes. This guarantees a clean reset (state, in-flight fetches, SSE
 * connections) every time the user opens a different session — no chance of
 * data from session A leaking into session B's render.
 */
function KeyedSessionPage() {
  const { id } = useParams<{ id: string }>();
  return <SessionPage key={id ?? 'none'} />;
}

export function App() {
  return (
    <div className="min-h-screen flex flex-col">
      <TopNav />
      <main className="flex-1">
        <Routes>
          <Route path="/" element={<UploadPage />} />
          <Route path="/session/:id" element={<KeyedSessionPage />} />
        </Routes>
      </main>
    </div>
  );
}

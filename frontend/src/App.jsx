import React, { useEffect, useState } from 'react';

const API_BASE = import.meta.env.VITE_API_BASE || '';
const OPERATOR_TOKEN = import.meta.env.VITE_OPERATOR_TOKEN || '';

function authHeaders() {
  return OPERATOR_TOKEN ? { Authorization: `Bearer ${OPERATOR_TOKEN}` } : {};
}

// Statuses considered "action required" and "pending" for the summary tiles.
const ACTION_STATES = new Set(['USER_ACTION_REQUIRED', 'VERIFICATION_REQUIRED', 'MORE_INFORMATION_REQUIRED']);
const PENDING_STATES = new Set(['DRAFT', 'APPROVED', 'SENT', 'ACKNOWLEDGED', 'WAITING', 'FOLLOW_UP_DUE', 'FOLLOW_UP_SENT']);

function summarize(requests) {
  const s = { total: requests.length, completed: 0, pending: 0, actionRequired: 0, rejected: 0 };
  for (const r of requests) {
    if (r.status === 'COMPLETED') s.completed++;
    else if (r.status === 'REJECTED') s.rejected++;
    else if (ACTION_STATES.has(r.status)) s.actionRequired++;
    else if (PENDING_STATES.has(r.status)) s.pending++;
  }
  return s;
}

function Tile({ label, value }) {
  return (
    <div style={styles.tile}>
      <div style={styles.tileValue}>{value}</div>
      <div style={styles.tileLabel}>{label}</div>
    </div>
  );
}

export default function App() {
  const [requests, setRequests] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  async function load() {
    try {
      setLoading(true);
      const res = await fetch(`${API_BASE}/api/requests`, { headers: authHeaders() });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setRequests(await res.json());
      setError(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    const id = setInterval(load, 15000);
    return () => clearInterval(id);
  }, []);

  const s = summarize(requests);

  return (
    <div style={styles.page}>
      <h1 style={styles.h1}>PrivacyPilot</h1>
      <p style={styles.subtitle}>
        Automates discovery, generation, submission, and response tracking of personal-data
        deletion requests. It does not delete data from third-party systems directly.
      </p>

      <div style={styles.tiles}>
        <Tile label="Total" value={s.total} />
        <Tile label="Completed" value={s.completed} />
        <Tile label="Pending" value={s.pending} />
        <Tile label="Action Required" value={s.actionRequired} />
        <Tile label="Rejected" value={s.rejected} />
      </div>

      {loading && <p>Loading…</p>}
      {error && <p style={styles.error}>Could not reach the API: {error}</p>}

      <table style={styles.table}>
        <thead>
          <tr>
            <th style={styles.th}>Company</th>
            <th style={styles.th}>Status</th>
            <th style={styles.th}>Last Action</th>
            <th style={styles.th}>Next Action</th>
          </tr>
        </thead>
        <tbody>
          {requests.map((r) => (
            <tr key={r.id}>
              <td style={styles.td}>{r.companyName}</td>
              <td style={styles.td}><span style={styles.badge}>{r.status}</span></td>
              <td style={styles.td}>{r.lastAction}</td>
              <td style={styles.td}>{r.nextAction}</td>
            </tr>
          ))}
          {!loading && requests.length === 0 && (
            <tr><td style={styles.td} colSpan={4}>No requests yet.</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

const styles = {
  page: { fontFamily: 'system-ui, sans-serif', maxWidth: 960, margin: '0 auto', padding: 24, color: '#1a1a1a' },
  h1: { marginBottom: 4 },
  subtitle: { color: '#555', marginTop: 0 },
  tiles: { display: 'flex', gap: 12, flexWrap: 'wrap', margin: '20px 0' },
  tile: { flex: '1 1 140px', background: '#f5f6f8', borderRadius: 8, padding: 16, textAlign: 'center' },
  tileValue: { fontSize: 32, fontWeight: 700 },
  tileLabel: { color: '#666', fontSize: 13, marginTop: 4 },
  table: { width: '100%', borderCollapse: 'collapse', marginTop: 12 },
  th: { textAlign: 'left', borderBottom: '2px solid #ddd', padding: '8px 6px', fontSize: 13, color: '#444' },
  td: { borderBottom: '1px solid #eee', padding: '8px 6px', fontSize: 14 },
  badge: { background: '#e8eefc', borderRadius: 12, padding: '2px 10px', fontSize: 12 },
  error: { color: '#b00020' },
};

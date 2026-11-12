import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Download, FileText, Loader2, RefreshCw, Search } from 'lucide-react';
import { api, downloadFile, errorMessage, errorText } from '../api/client';
import { useApi, useDebounced } from '../hooks';
import { Alert, EmptyState, ErrorState, LoadingBlock, PageHeader, Pagination, StatusBadge } from '../components/ui';
import { fmtBytes, fmtDateTime } from '../utils/format';

export default function Reports() {
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const search = useDebounced(q.trim());
  const reports = useApi('/reports', { params: { q: search || undefined, page, size: 10 } });
  const [busy, setBusy] = useState('');
  const [message, setMessage] = useState(null);

  async function download(r) {
    setBusy(`dl-${r.id}`);
    setMessage(null);
    try {
      await downloadFile(`/incidents/${r.id}/report`, `fall-report-${r.incidentId}.pdf`);
      reports.reload(true);
    } catch (e) {
      setMessage({ tone: 'danger', text: await errorMessage(e, 'Download failed.') });
    } finally {
      setBusy('');
    }
  }

  async function generate(r) {
    setBusy(`gen-${r.id}`);
    setMessage(null);
    try {
      await api.post(`/incidents/${r.id}/report`);
      setMessage({ tone: 'success', text: `Report for ${r.incidentId} generated.` });
      reports.reload(true);
    } catch (e) {
      setMessage({ tone: 'danger', text: errorText(e, 'Report generation failed.') });
    } finally {
      setBusy('');
    }
  }

  const data = reports.data;
  return (
    <>
      <PageHeader eyebrow="Documentation" title="Incident reports" description="PDF reports with the evidence frame, AI summary and caregiver response for every incident." />
      {message && <Alert tone={message.tone}>{message.text}</Alert>}
      <section className="panel">
        <div className="filters">
          <label className="searchBox">
            <Search aria-hidden />
            <input value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} placeholder="Search by resident or incident ID" aria-label="Search reports" />
          </label>
        </div>
        {reports.error && !data ? <ErrorState message={reports.error} onRetry={() => reports.reload()} />
          : !data ? <LoadingBlock label="Loading reports…" />
            : !data.content.length ? (
              <EmptyState icon={FileText} title={search ? 'No reports match your search' : 'No reports yet'}>
                {search ? 'Try another search term.' : 'A report is generated automatically for every recorded incident.'}
              </EmptyState>
            ) : (
              <>
                <div className="tableWrap">
                  <table>
                    <thead><tr><th>Incident</th><th>Resident</th><th>Incident time</th><th>Status</th><th>Report</th><th className="num">Size</th><th><span className="visuallyHidden">Actions</span></th></tr></thead>
                    <tbody>
                      {data.content.map((r) => (
                        <tr key={r.id}>
                          <td><Link className="strong" to={`/app/incidents/${r.id}`}>{r.incidentId}</Link></td>
                          <td>{r.elderlyName}</td>
                          <td>{fmtDateTime(r.timestamp)}</td>
                          <td><StatusBadge status={r.status} /></td>
                          <td>{r.pdfAvailable ? <span className="pill ok">Generated {fmtDateTime(r.pdfGeneratedAt)}</span> : <span className="pill muted">Not generated</span>}</td>
                          <td className="num">{fmtBytes(r.sizeBytes)}</td>
                          <td className="rowActions">
                            <button type="button" className="btn outline small" onClick={() => download(r)} disabled={busy === `dl-${r.id}`}>
                              {busy === `dl-${r.id}` ? <Loader2 className="spin" aria-hidden /> : <Download aria-hidden />} Download</button>
                            <button type="button" className="btn ghost small" onClick={() => generate(r)} disabled={busy === `gen-${r.id}`} title={r.pdfAvailable ? 'Regenerate with the latest data' : 'Generate report'}>
                              {busy === `gen-${r.id}` ? <Loader2 className="spin" aria-hidden /> : <RefreshCw aria-hidden />} {r.pdfAvailable ? 'Regenerate' : 'Generate'}</button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <Pagination page={data.page} size={data.size} totalPages={data.totalPages} totalElements={data.totalElements} onPage={setPage} />
              </>
            )}
      </section>
    </>
  );
}

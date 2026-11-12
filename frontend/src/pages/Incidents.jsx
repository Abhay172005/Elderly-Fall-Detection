import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { CircleAlert, Download, Loader2, Search, Trash2, X } from 'lucide-react';
import { api, downloadFile, errorMessage, errorText } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useApi, useDebounced } from '../hooks';
import { Alert, ConfirmDialog, EmailBadge, EmptyState, ErrorState, LoadingBlock, PageHeader, Pagination, StatusBadge } from '../components/ui';
import { fmtDateTime, fmtPercent } from '../utils/format';

const FILTERS = [['', 'All'], ['VERIFIED', 'Verified'], ['PENDING', 'Pending'], ['RESOLVED', 'Resolved'], ['FALSE_ALARM', 'False alarm'], ['VERIFIED,PENDING', 'Open']];
const SORTS = [['timestamp,desc', 'Newest first'], ['timestamp,asc', 'Oldest first'], ['confidence,desc', 'Highest confidence'], ['confidence,asc', 'Lowest confidence']];

export default function Incidents() {
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState(params.get('q') || '');
  const search = useDebounced(q.trim());
  const status = params.get('status') || '';
  const from = params.get('from') || '';
  const to = params.get('to') || '';
  const sort = params.get('sort') || 'timestamp,desc';
  const page = Number(params.get('page') || 0);
  const size = Number(params.get('size') || 10);
  const [sortField, direction] = sort.split(',');
  const [toDelete, setToDelete] = useState(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState('');
  const [exporting, setExporting] = useState(false);
  const [notice, setNotice] = useState('');

  const query = { q: search || undefined, status: status || undefined, from: from || undefined, to: to || undefined, sort: sortField, direction, page, size };
  const list = useApi('/incidents', { params: query });

  function update(changes) {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([k, v]) => (v === '' || v === undefined || v === null ? next.delete(k) : next.set(k, v)));
    if (!('page' in changes)) next.delete('page');
    setParams(next, { replace: true });
  }

  async function confirmDelete() {
    setDeleting(true);
    setDeleteError('');
    try {
      await api.delete(`/incidents/${toDelete.id}`);
      setNotice(`Incident ${toDelete.incidentId} was deleted.`);
      setToDelete(null);
      if (list.data.content.length === 1 && page > 0) update({ page: page - 1 });
      else list.reload(true);
    } catch (e) {
      setDeleteError(errorText(e, 'The incident could not be deleted.'));
    } finally {
      setDeleting(false);
    }
  }

  async function exportCsv() {
    setExporting(true);
    try {
      await downloadFile('/incidents/export', 'incidents.csv', { q: search || undefined, status: status || undefined, from: from || undefined, to: to || undefined });
    } catch (e) {
      setNotice(await errorMessage(e, 'Export failed.'));
    } finally {
      setExporting(false);
    }
  }

  const hasFilters = Boolean(search || status || from || to);
  const data = list.data;

  return (
    <>
      <PageHeader eyebrow="Safety records" title="Incident history" description="Search, filter and manage every recorded fall incident."
                  actions={<button type="button" className="btn outline" onClick={exportCsv} disabled={exporting || !data?.totalElements}>
                    {exporting ? <Loader2 className="spin" aria-hidden /> : <Download aria-hidden />} Export CSV</button>} />
      {notice && <Alert tone="info" action={<button type="button" className="iconBtn" onClick={() => setNotice('')} aria-label="Dismiss"><X /></button>}>{notice}</Alert>}

      <section className="panel">
        <div className="filters">
          <label className="searchBox">
            <Search aria-hidden />
            <input value={q} onChange={(e) => { setQ(e.target.value); update({ q: e.target.value.trim() }); }} placeholder="Search by resident, camera or incident ID" aria-label="Search incidents" />
          </label>
          <div className="filterRow">
            <div className="chips" role="group" aria-label="Status filter">
              {FILTERS.map(([value, label]) => (
                <button type="button" key={label} className={status === value ? 'chip active' : 'chip'} onClick={() => update({ status: value })}>{label}</button>
              ))}
            </div>
            <div className="filterInputs">
              <label>From<input type="date" value={from} max={to || undefined} onChange={(e) => update({ from: e.target.value })} /></label>
              <label>To<input type="date" value={to} min={from || undefined} onChange={(e) => update({ to: e.target.value })} /></label>
              <label>Sort<select value={sort} onChange={(e) => update({ sort: e.target.value })}>{SORTS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
              {hasFilters && <button type="button" className="linkBtn" onClick={() => { setQ(''); setParams({}, { replace: true }); }}>Clear filters</button>}
            </div>
          </div>
        </div>

        {list.error && !data ? <ErrorState message={list.error} onRetry={() => list.reload()} />
          : !data ? <LoadingBlock label="Loading incidents…" />
            : data.content.length === 0 ? (
              <EmptyState icon={CircleAlert} title={hasFilters ? 'No incidents match these filters' : 'No incidents recorded yet'}
                          action={!hasFilters && <Link className="btn outline" to="/app/monitoring">Analyse a video</Link>}>
                {hasFilters ? 'Try a different search or clear the filters.' : 'Confirmed fall detections will appear here.'}
              </EmptyState>
            ) : (
              <>
                <div className={`tableWrap ${list.loading ? 'refreshing' : ''}`}>
                  <table>
                    <thead>
                      <tr><th>Incident</th><th>Resident</th><th>Camera</th><th>Date &amp; time</th><th className="num">Confidence</th><th>Status</th><th>Email</th><th><span className="visuallyHidden">Actions</span></th></tr>
                    </thead>
                    <tbody>
                      {data.content.map((i) => (
                        <tr key={i.id} onClick={() => navigate(`/app/incidents/${i.id}`)} className="clickableRow">
                          <td><Link to={`/app/incidents/${i.id}`} onClick={(e) => e.stopPropagation()} className="strong">{i.incidentId}</Link>
                            {i.status !== 'RESOLVED' && i.status !== 'FALSE_ALARM' && !i.acknowledgedAt && <span className="newTag">Unacknowledged</span>}</td>
                          <td>{i.elderlyName}{i.elderlyAge ? <small className="muted">, {i.elderlyAge}</small> : null}</td>
                          <td>{i.cameraName}</td>
                          <td>{fmtDateTime(i.timestamp)}</td>
                          <td className="num">{fmtPercent(i.confidence)}</td>
                          <td><StatusBadge status={i.status} /></td>
                          <td><EmailBadge status={i.emailStatus} /></td>
                          <td className="rowActions" onClick={(e) => e.stopPropagation()}>
                            <Link className="btn outline small" to={`/app/incidents/${i.id}`}>View</Link>
                            {isAdmin && <button type="button" className="iconBtn danger" aria-label={`Delete ${i.incidentId}`} title="Delete" onClick={() => { setDeleteError(''); setToDelete(i); }}><Trash2 /></button>}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="tableFoot">
                  <Pagination page={data.page} size={data.size} totalPages={data.totalPages} totalElements={data.totalElements} onPage={(p) => update({ page: p })} />
                  <label className="pageSize">Rows<select value={size} onChange={(e) => update({ size: e.target.value })}>{[10, 25, 50].map((n) => <option key={n}>{n}</option>)}</select></label>
                </div>
              </>
            )}
      </section>

      {toDelete && (
        <ConfirmDialog title="Delete incident?" danger busy={deleting} error={deleteError} confirmLabel="Delete permanently"
                       message={`Incident ${toDelete.incidentId} for ${toDelete.elderlyName}, including its captured frame and PDF report, will be permanently deleted.`}
                       onConfirm={confirmDelete} onCancel={() => setToDelete(null)} />
      )}
    </>
  );
}

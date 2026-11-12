import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { AlertTriangle, ArrowLeft, Bot, CheckCircle2, ClipboardCheck, Download, FileText, Hand, Loader2, Mail, RefreshCw, Trash2 } from 'lucide-react';
import { api, downloadFile, errorMessage, errorText } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useApi } from '../hooks';
import { Alert, AuthImage, ConfirmDialog, EmailBadge, ErrorState, Field, LoadingBlock, Modal, StatusBadge } from '../components/ui';
import { fmtDateTimeLong, fmtPercent, fmtSeconds, POSTURE_LABELS } from '../utils/format';

export default function IncidentDetails() {
  const { id } = useParams();
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const incident = useApi(`/incidents/${id}`);
  const [dialog, setDialog] = useState(null); // 'ack' | 'resolve' | 'delete' | 'confirm'
  const [note, setNote] = useState('');
  const [outcome, setOutcome] = useState('RESOLVED');
  const [busy, setBusy] = useState('');
  const [actionError, setActionError] = useState('');
  const [message, setMessage] = useState(null);

  const i = incident.data;
  const open = i && (i.status === 'VERIFIED' || i.status === 'PENDING');

  async function act(key, request, success) {
    setBusy(key);
    setActionError('');
    try {
      const { data } = await request();
      incident.setData(data);
      setDialog(null);
      setNote('');
      setMessage({ tone: 'success', text: success(data) });
    } catch (e) {
      const text = errorText(e, 'The action failed.');
      if (dialog) setActionError(text);
      else setMessage({ tone: 'danger', text });
    } finally {
      setBusy('');
    }
  }

  async function download() {
    setBusy('pdf');
    try {
      await downloadFile(`/incidents/${id}/report`, `fall-report-${i.incidentId}.pdf`);
      incident.reload(true);
    } catch (e) {
      setMessage({ tone: 'danger', text: await errorMessage(e, 'The report could not be downloaded.') });
    } finally {
      setBusy('');
    }
  }

  async function remove() {
    setBusy('delete');
    try {
      await api.delete(`/incidents/${id}`);
      navigate('/app/incidents', { replace: true });
    } catch (e) {
      setActionError(errorText(e, 'The incident could not be deleted.'));
      setBusy('');
    }
  }

  if (incident.error && !i) {
    return (
      <>
        <Link className="back" to="/app/incidents"><ArrowLeft aria-hidden /> All incidents</Link>
        <ErrorState message={incident.error} onRetry={() => incident.reload()} />
      </>
    );
  }
  if (!i) return <LoadingBlock label="Loading incident…" />;
  const d = i.detection || {};

  return (
    <>
      <Link className="back" to="/app/incidents"><ArrowLeft aria-hidden /> All incidents</Link>
      <div className="detailHead">
        <div>
          <p className="eyebrow">{i.source === 'MANUAL' ? 'Manually reported incident' : 'Fall incident'}</p>
          <h1>{i.incidentId}</h1>
          <p className="muted">{i.elderlyName} · {i.cameraName} · {fmtDateTimeLong(i.timestamp)}</p>
        </div>
        <StatusBadge status={i.status} />
      </div>

      {message && <Alert tone={message.tone}>{message.text}</Alert>}
      {open && !i.acknowledgedAt && (
        <Alert tone={i.status === 'VERIFIED' ? 'danger' : 'warning'} title={i.status === 'VERIFIED' ? 'Response required' : 'Review required'}
               action={<button type="button" className="btn primary small" onClick={() => { setActionError(''); setDialog('ack'); }}><Hand aria-hidden /> Acknowledge</button>}>
          {i.status === 'VERIFIED' ? 'A verified fall has not been acknowledged yet. Check on the resident and acknowledge the incident.'
            : 'This potential fall could not be verified automatically. Review the frame and confirm or dismiss it.'}
        </Alert>
      )}

      <div className="actionBar">
        {open && !i.acknowledgedAt && <button type="button" className="btn outline" onClick={() => { setActionError(''); setDialog('ack'); }}><Hand aria-hidden /> Acknowledge</button>}
        {open && <button type="button" className="btn primary" onClick={() => { setActionError(''); setOutcome('RESOLVED'); setDialog('resolve'); }}><ClipboardCheck aria-hidden /> Resolve</button>}
        {i.status === 'PENDING' && <button type="button" className="btn outline" onClick={() => { setActionError(''); setDialog('confirm'); }}><AlertTriangle aria-hidden /> Confirm as fall</button>}
        <button type="button" className="btn outline" onClick={download} disabled={busy === 'pdf'}>{busy === 'pdf' ? <Loader2 className="spin" aria-hidden /> : <Download aria-hidden />} Download PDF</button>
        <button type="button" className="btn ghost" disabled={busy === 'report'} onClick={() => act('report', () => api.post(`/incidents/${id}/report`), () => 'The PDF report was regenerated.')}>
          {busy === 'report' ? <Loader2 className="spin" aria-hidden /> : <RefreshCw aria-hidden />} Regenerate report</button>
        <button type="button" className="btn ghost" disabled={busy === 'email'} onClick={() => act('email', () => api.post(`/incidents/${id}/notify`), (x) => `Email status: ${x.emailStatus.replace('_', ' ').toLowerCase()}. ${x.emailDetail || ''}`)}>
          {busy === 'email' ? <Loader2 className="spin" aria-hidden /> : <Mail aria-hidden />} Send email alert</button>
        {isAdmin && <button type="button" className="btn ghost dangerText" onClick={() => { setActionError(''); setDialog('delete'); }}><Trash2 aria-hidden /> Delete</button>}
      </div>

      <div className="detailGrid">
        <div className="detailMain">
          <section className="panel">
            <p className="eyebrow">Captured fall frame</p>
            {i.imageAvailable
              ? <AuthImage src={`/incidents/${id}/image`} alt={`Captured frame for ${i.incidentId}`} className="evidenceImage" />
              : <div className="imagePlaceholder evidenceImage"><span>No captured frame is stored for this incident.</span></div>}
            <dl className="infoGrid">
              <div><dt>Resident</dt><dd>{i.elderlyName}</dd></div>
              <div><dt>Age</dt><dd>{i.elderlyAge ? `${i.elderlyAge} years` : 'Not recorded'}</dd></div>
              <div><dt>Camera</dt><dd>{i.cameraName}</dd></div>
              <div><dt>Date &amp; time</dt><dd>{fmtDateTimeLong(i.timestamp)}</dd></div>
              <div><dt>Detection confidence</dt><dd>{fmtPercent(i.confidence)}</dd></div>
              <div><dt>Status</dt><dd><StatusBadge status={i.status} /></dd></div>
            </dl>
          </section>

          {i.source !== 'MANUAL' && (
            <section className="panel">
              <p className="eyebrow">Detection information</p>
              <dl className="infoGrid">
                <div><dt>Verification</dt><dd>{(d.verificationStatus || d.outcome || '—').replace('_', ' ').toLowerCase()}</dd></div>
                <div><dt>Fall moment in video</dt><dd>{d.fallStartSeconds != null ? fmtSeconds(d.fallStartSeconds) : '—'}</dd></div>
                <div><dt>Observed on the ground</dt><dd>{d.observedDownSeconds != null ? `${fmtSeconds(d.observedDownSeconds)} (window ${d.verificationSeconds} s)` : '—'}</dd></div>
                <div><dt>Posture</dt><dd>{d.postureBefore ? `${POSTURE_LABELS[d.postureBefore] || d.postureBefore} → ${POSTURE_LABELS[d.postureAfter] || d.postureAfter}` : '—'}</dd></div>
                <div><dt>People in video</dt><dd>{d.maxPeopleDetected ?? '—'}</dd></div>
                <div><dt>Model</dt><dd>{d.modelName || '—'}{d.framesAnalyzed ? ` · ${d.framesAnalyzed} frames` : ''}</dd></div>
                <div><dt>Source video</dt><dd className="wrap">{d.videoName || '—'}</dd></div>
                <div><dt>Threshold</dt><dd>{fmtPercent(d.confidenceThreshold)}</dd></div>
              </dl>
              {d.reasons?.length > 0 && (
                <div className="evidence"><p className="eyebrow">Evidence</p><ul>{d.reasons.map((r) => <li key={r}>{r}</li>)}</ul></div>
              )}
            </section>
          )}
        </div>

        <div className="detailSide">
          <section className="panel">
            <div className="panelHead">
              <div><p className="eyebrow">Incident summary</p><h2>What happened</h2></div>
              <span className={`pill ${i.aiSource === 'GROQ' ? 'ok' : 'muted'}`} title={i.aiModel || ''}><Bot aria-hidden /> {i.aiSource === 'GROQ' ? 'AI generated' : 'Template'}</span>
            </div>
            <p>{i.aiSummary || 'No summary available.'}</p>
            <p className="eyebrow">Caregiver recommendation</p>
            <p>{i.caregiverRecommendation || 'Check on the resident in person.'}</p>
            <p className="muted small">{i.aiSource === 'GROQ' ? `Generated by ${i.aiModel} via Groq from detection data. ` : 'AI was unavailable; a factual template was used. '}Not a medical assessment.</p>
          </section>

          <section className="panel">
            <p className="eyebrow">Response timeline</p>
            <ol className="responseTimeline">
              <li className="done"><b>Detected</b><span>{fmtDateTimeLong(i.timestamp)}</span></li>
              <li className={i.acknowledgedAt ? 'done' : ''}><b>Acknowledged</b>
                <span>{i.acknowledgedAt ? `${fmtDateTimeLong(i.acknowledgedAt)} by ${i.acknowledgedBy}` : 'Not yet'}</span>
                {i.acknowledgementNote && <q>{i.acknowledgementNote}</q>}</li>
              <li className={i.resolvedAt ? 'done' : ''}><b>{i.status === 'FALSE_ALARM' ? 'Marked false alarm' : 'Resolved'}</b>
                <span>{i.resolvedAt ? `${fmtDateTimeLong(i.resolvedAt)} by ${i.resolvedBy}` : 'Open'}</span>
                {i.resolutionNote && <q>{i.resolutionNote}</q>}</li>
            </ol>
          </section>

          <section className="panel">
            <p className="eyebrow">Notifications &amp; report</p>
            <ul className="healthList">
              <li><Mail aria-hidden /><span><b>Email alert</b><small>{i.emailDetail || 'Not attempted yet.'}</small></span><EmailBadge status={i.emailStatus} /></li>
              <li><FileText aria-hidden /><span><b>PDF report</b><small>{i.pdfGeneratedAt ? `Generated ${fmtDateTimeLong(i.pdfGeneratedAt)}` : 'Generated on first download.'}</small></span>
                <span className={`pill ${i.pdfAvailable ? 'ok' : 'muted'}`}>{i.pdfAvailable ? 'Ready' : 'Not generated'}</span></li>
            </ul>
          </section>
        </div>
      </div>

      {dialog === 'ack' && (
        <Modal title="Acknowledge incident" onClose={() => setDialog(null)} footer={(
          <>
            <button type="button" className="btn outline" onClick={() => setDialog(null)}>Cancel</button>
            <button type="button" className="btn primary" disabled={busy === 'ack'} onClick={() => act('ack', () => api.put(`/incidents/${id}/acknowledge`, { note: note.trim() || null }), () => 'Incident acknowledged.')}>
              {busy === 'ack' && <Loader2 className="spin" aria-hidden />} Acknowledge</button>
          </>
        )}>
          <p>Confirms that a caregiver has seen this incident and is responding.</p>
          <Field label="Note (optional)" htmlFor="ackNote" hint={`${note.length}/1000`}>
            <textarea id="ackNote" rows={3} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. On my way to the living room." />
          </Field>
          {actionError && <p className="formError">{actionError}</p>}
        </Modal>
      )}

      {dialog === 'resolve' && (
        <Modal title="Close incident" onClose={() => setDialog(null)} footer={(
          <>
            <button type="button" className="btn outline" onClick={() => setDialog(null)}>Cancel</button>
            <button type="button" className="btn primary" disabled={busy === 'resolve'} onClick={() => act('resolve', () => api.put(`/incidents/${id}/resolve`, { outcome, note: note.trim() || null }),
              () => (outcome === 'FALSE_ALARM' ? 'Incident marked as a false alarm.' : 'Incident resolved.'))}>
              {busy === 'resolve' && <Loader2 className="spin" aria-hidden />} Save outcome</button>
          </>
        )}>
          <fieldset className="radioCards">
            <legend>Outcome</legend>
            <label className={outcome === 'RESOLVED' ? 'active' : ''}><input type="radio" name="outcome" value="RESOLVED" checked={outcome === 'RESOLVED'} onChange={() => setOutcome('RESOLVED')} />
              <CheckCircle2 aria-hidden /><span><b>Resolved</b><small>A fall occurred and the resident has been helped.</small></span></label>
            <label className={outcome === 'FALSE_ALARM' ? 'active' : ''}><input type="radio" name="outcome" value="FALSE_ALARM" checked={outcome === 'FALSE_ALARM'} onChange={() => setOutcome('FALSE_ALARM')} />
              <AlertTriangle aria-hidden /><span><b>False alarm</b><small>No fall occurred (e.g. the resident lay down on purpose).</small></span></label>
          </fieldset>
          <Field label="Resolution note (optional)" htmlFor="resNote" hint={`${note.length}/1000`}>
            <textarea id="resNote" rows={3} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. Resident helped up, no visible injury, family informed." />
          </Field>
          {actionError && <p className="formError">{actionError}</p>}
        </Modal>
      )}

      {dialog === 'confirm' && (
        <ConfirmDialog title="Confirm as a verified fall?" confirmLabel="Confirm fall" busy={busy === 'confirm'} error={actionError}
                       message="The incident status changes to Verified and the report is regenerated. Use this when a caregiver has confirmed that a fall occurred."
                       onCancel={() => setDialog(null)}
                       onConfirm={() => act('confirm', () => api.put(`/incidents/${id}`, { status: 'VERIFIED' }), () => 'Incident confirmed as a verified fall.')} />
      )}

      {dialog === 'delete' && (
        <ConfirmDialog title="Delete incident?" danger confirmLabel="Delete permanently" busy={busy === 'delete'} error={actionError}
                       message={`Incident ${i.incidentId}, its captured frame and its PDF report will be permanently deleted.`}
                       onCancel={() => setDialog(null)} onConfirm={remove} />
      )}
    </>
  );
}

import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';
import {
  AlertTriangle, Ban, CheckCircle2, CircleDashed, CircleSlash, Clock3, FileVideo, Loader2, Play, RefreshCw, Settings2, ShieldCheck, Upload, Video, X, XCircle,
} from 'lucide-react';
import { api, errorText } from '../api/client';
import { useApi } from '../hooks';
import { Alert, AuthImage, PageHeader } from '../components/ui';
import { fmtBytes, fmtDateTimeLong, fmtDuration, fmtPercent, fmtSeconds, POSTURE_LABELS } from '../utils/format';

const EXTENSIONS = ['mp4', 'webm', 'mov', 'avi', 'mkv'];
const MAX_MB = Number(import.meta.env.VITE_MAX_UPLOAD_MB || 200);
const ACTIVE_JOB_KEY = 'sefd.activeJob';
const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELLED'];

const STAGES = [
  ['upload', 'Uploading video'],
  ['extract', 'Extracting frames'],
  ['person', 'Detecting person'],
  ['posture', 'Analysing posture'],
  ['verify', 'Verifying potential fall'],
  ['save', 'Saving incident & AI summary'],
  ['report', 'Generating report'],
];

/** Derives each pipeline stage's state from real upload progress and ML job data; nothing is simulated. */
function stageStates(phase, uploadPct, job) {
  const st = {};
  const stage = job?.stage;
  const p = job?.progress || {};
  const r = job?.result;
  const done = job?.status === 'COMPLETED';
  const ended = job && TERMINAL.includes(job.status);
  const order = ['QUEUED', 'VALIDATING_VIDEO', 'EXTRACTING_FRAMES', 'DETECTING_PERSON', 'ANALYZING_POSTURE', 'VERIFYING_FALL', 'SAVING_INCIDENT', 'COMPLETED'];
  const reached = (s) => order.indexOf(stage) >= order.indexOf(s) || done;

  st.upload = phase === 'UPLOADING' ? { s: 'active', note: `${uploadPct}%` } : job ? { s: 'done' } : { s: 'pending' };
  if (!job) {
    STAGES.slice(1).forEach(([k]) => { st[k] = { s: 'pending' }; });
    return st;
  }
  const failNow = (k) => (job.status === 'FAILED' || job.status === 'CANCELLED') && !st[k];

  st.extract = stage === 'QUEUED' ? { s: 'active', note: 'Queued' }
    : ['VALIDATING_VIDEO', 'EXTRACTING_FRAMES'].includes(stage) ? { s: 'active' }
      : (p.framesAnalyzed || done) ? { s: 'done', note: p.framesAnalyzed ? `${p.framesAnalyzed} frames sampled` : undefined } : null;
  if (!st.extract) st.extract = failNow('extract') ? { s: job.status === 'FAILED' ? 'failed' : 'skipped' } : { s: 'pending' };

  const personFound = p.personDetected || r?.personDetected;
  st.person = personFound ? { s: 'done' }
    : done ? { s: 'skipped', note: 'No person found' }
      : reached('DETECTING_PERSON') && !ended ? { s: 'active' } : ended ? { s: 'skipped' } : { s: 'pending' };

  st.posture = done && personFound ? { s: 'done' }
    : personFound && !ended ? { s: 'active', note: (p.postures || []).map((x) => POSTURE_LABELS[x.posture] || x.posture).slice(0, 3).join(', ') || undefined }
      : ended || (done && !personFound) ? { s: 'skipped' } : { s: 'pending' };

  const triggered = r ? r.verificationStatus !== 'NOT_TRIGGERED' : Boolean(p.verification);
  st.verify = stage === 'VERIFYING_FALL' && !ended ? {
    s: 'active', note: p.verification ? `${fmtSeconds(p.verification.elapsedSeconds)} of ${p.verification.windowSeconds} s` : undefined,
  } : done && triggered ? { s: 'done', note: r?.verificationStatus?.replace('_', ' ').toLowerCase() }
    : done ? { s: 'skipped', note: 'No potential fall' } : ended ? { s: 'skipped' } : { s: 'pending' };

  st.save = stage === 'SAVING_INCIDENT' && !ended ? { s: 'active' }
    : job.incident ? { s: 'done', note: job.incident.incidentId }
      : job.incidentError ? { s: 'failed', note: 'Not saved' }
        : ended ? { s: 'skipped', note: done ? 'No incident to record' : undefined } : { s: 'pending' };

  st.report = stage === 'SAVING_INCIDENT' && !ended ? { s: 'pending', note: 'Waiting for incident' }
    : job.incident?.pdfAvailable ? { s: 'done' }
      : job.incident ? { s: 'failed', note: 'Report unavailable' } : ended ? { s: 'skipped' } : { s: 'pending' };
  return st;
}

const STAGE_ICON = { done: CheckCircle2, active: Loader2, pending: CircleDashed, skipped: CircleSlash, failed: XCircle };

export default function Monitoring() {
  const settings = useApi('/settings');
  const inputRef = useRef(null);
  const abortRef = useRef(null);
  const [file, setFile] = useState(null);
  const [preview, setPreview] = useState('');
  const [meta, setMeta] = useState(null);
  const [previewError, setPreviewError] = useState(false);
  const [drag, setDrag] = useState(false);
  const [phase, setPhase] = useState('IDLE'); // IDLE | SELECTED | UPLOADING | PROCESSING | DONE
  const [uploadPct, setUploadPct] = useState(0);
  const [job, setJob] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => () => preview && URL.revokeObjectURL(preview), [preview]);

  // Resume a job that was running when the user navigated away.
  useEffect(() => {
    const saved = sessionStorage.getItem(ACTIVE_JOB_KEY);
    if (!saved) return;
    const { jobId } = JSON.parse(saved);
    api.get(`/detection/jobs/${jobId}`).then(({ data }) => {
      setJob(data);
      setPhase(TERMINAL.includes(data.status) ? 'DONE' : 'PROCESSING');
    }).catch(() => sessionStorage.removeItem(ACTIVE_JOB_KEY));
  }, []);

  // Poll the job while it runs.
  useEffect(() => {
    if (phase !== 'PROCESSING' || !job?.jobId) return undefined;
    let stop = false;
    const timer = setInterval(async () => {
      try {
        const { data } = await api.get(`/detection/jobs/${job.jobId}`);
        if (stop) return;
        setJob(data);
        if (TERMINAL.includes(data.status)) {
          setPhase('DONE');
          sessionStorage.removeItem(ACTIVE_JOB_KEY);
        }
      } catch (e) {
        if (!stop && e.response?.status === 404) {
          setError('The detection job is no longer available on the ML service (it may have restarted).');
          setPhase('DONE');
          sessionStorage.removeItem(ACTIVE_JOB_KEY);
        }
      }
    }, 1500);
    return () => { stop = true; clearInterval(timer); };
  }, [phase, job?.jobId]);

  const choose = useCallback((selected) => {
    if (!selected) return;
    const ext = selected.name.split('.').pop().toLowerCase();
    if (!EXTENSIONS.includes(ext)) {
      setError(`"${selected.name}" is not a supported video. Use ${EXTENSIONS.map((e) => e.toUpperCase()).join(', ')}.`);
      return;
    }
    if (selected.size > MAX_MB * 1024 * 1024) {
      setError(`The video is ${fmtBytes(selected.size)}; the maximum upload size is ${MAX_MB} MB.`);
      return;
    }
    if (selected.size === 0) {
      setError('The selected file is empty.');
      return;
    }
    if (preview) URL.revokeObjectURL(preview);
    setFile(selected);
    setPreview(URL.createObjectURL(selected));
    setMeta(null);
    setPreviewError(false);
    setJob(null);
    setError('');
    setPhase('SELECTED');
  }, [preview]);

  async function start() {
    if (!file) return;
    const controller = new AbortController();
    abortRef.current = controller;
    setPhase('UPLOADING');
    setUploadPct(0);
    setJob(null);
    setError('');
    const form = new FormData();
    form.append('video', file);
    try {
      const { data } = await api.post('/detection/jobs', form, {
        timeout: 0,
        signal: controller.signal,
        onUploadProgress: (e) => e.total && setUploadPct(Math.round((e.loaded / e.total) * 100)),
      });
      setJob(data);
      setPhase(TERMINAL.includes(data.status) ? 'DONE' : 'PROCESSING');
      sessionStorage.setItem(ACTIVE_JOB_KEY, JSON.stringify({ jobId: data.jobId }));
    } catch (e) {
      if (axios.isCancel(e)) {
        setPhase('SELECTED');
        setError('Upload cancelled.');
      } else {
        setPhase('SELECTED');
        setError(errorText(e, 'The video could not be submitted for analysis.'));
      }
    } finally {
      abortRef.current = null;
    }
  }

  async function cancel() {
    if (phase === 'UPLOADING') {
      abortRef.current?.abort();
      return;
    }
    if (!job?.jobId) return;
    setBusy(true);
    try {
      const { data } = await api.delete(`/detection/jobs/${job.jobId}`);
      setJob(data);
    } catch (e) {
      setError(errorText(e, 'Could not cancel the analysis.'));
    } finally {
      setBusy(false);
    }
  }

  async function retryReport() {
    setBusy(true);
    try {
      const { data } = await api.post(`/detection/jobs/${job.jobId}/report`);
      setJob(data);
    } catch (e) {
      setError(errorText(e, 'Retry failed.'));
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    if (preview) URL.revokeObjectURL(preview);
    setFile(null);
    setPreview('');
    setMeta(null);
    setJob(null);
    setError('');
    setPhase('IDLE');
    sessionStorage.removeItem(ACTIVE_JOB_KEY);
    if (inputRef.current) inputRef.current.value = '';
  }

  const running = phase === 'UPLOADING' || phase === 'PROCESSING';
  const states = stageStates(phase, uploadPct, job);
  const p = job?.progress || {};
  const cfg = settings.data;

  return (
    <>
      <PageHeader eyebrow="Live monitoring" title="Video fall analysis"
                  description="Upload camera footage. YOLO Pose analyses every person's posture and verifies any potential fall before an incident is recorded." />

      <div className="monitorGrid">
        <section className="panel">
          <div className="panelHead">
            <div><p className="eyebrow">Video source</p><h2>{file ? file.name : job ? job.videoName : 'Select a video'}</h2></div>
            {file && !running && <button type="button" className="btn outline small" onClick={() => inputRef.current?.click()}>Change video</button>}
          </div>
          <input ref={inputRef} className="visuallyHidden" type="file" accept=".mp4,.webm,.mov,.avi,.mkv,video/*"
                 onChange={(e) => choose(e.target.files?.[0])} aria-label="Choose a video file" />
          {!file && !job ? (
            <div className={`dropZone ${drag ? 'dragging' : ''}`} role="button" tabIndex={0}
                 onClick={() => inputRef.current?.click()} onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && inputRef.current?.click()}
                 onDragOver={(e) => { e.preventDefault(); setDrag(true); }} onDragLeave={() => setDrag(false)}
                 onDrop={(e) => { e.preventDefault(); setDrag(false); choose(e.dataTransfer.files?.[0]); }}>
              <span className="dropIcon"><Upload aria-hidden /></span>
              <b>Drag and drop a video here</b>
              <span>or <u>browse your files</u></span>
              <small>MP4, WEBM, MOV, AVI or MKV · up to {MAX_MB} MB</small>
            </div>
          ) : file ? (
            <>
              {!previewError ? (
                <video className="videoPreview" controls src={preview} onError={() => setPreviewError(true)}
                       onLoadedMetadata={(e) => setMeta({ duration: e.currentTarget.duration, width: e.currentTarget.videoWidth, height: e.currentTarget.videoHeight })} />
              ) : (
                <div className="imagePlaceholder videoPreview"><FileVideo aria-hidden /><span>This browser cannot preview the format. It can still be analysed.</span></div>
              )}
              <dl className="metaGrid">
                <div><dt>File size</dt><dd>{fmtBytes(file.size)}</dd></div>
                <div><dt>Format</dt><dd>{file.name.split('.').pop().toUpperCase()}</dd></div>
                <div><dt>Duration</dt><dd>{meta ? fmtDuration(meta.duration) : '—'}</dd></div>
                <div><dt>Resolution</dt><dd>{meta?.width ? `${meta.width}×${meta.height}` : '—'}</dd></div>
              </dl>
            </>
          ) : (
            <div className="imagePlaceholder videoPreview"><Video aria-hidden /><span>Resumed analysis of {job.videoName}. The preview is not available after navigating away.</span></div>
          )}

          <div className="buttonRow">
            {(phase === 'SELECTED' || (phase === 'DONE' && file)) && (
              <button type="button" className="btn primary" onClick={start}>
                {phase === 'DONE' ? <><RefreshCw aria-hidden /> Analyse again</> : <><Play aria-hidden /> Start detection</>}
              </button>
            )}
            {running && (
              <button type="button" className="btn outline" onClick={cancel} disabled={busy || job?.status === 'CANCELLED'}>
                <Ban aria-hidden /> {phase === 'UPLOADING' ? 'Cancel upload' : 'Cancel detection'}
              </button>
            )}
            {(file || job) && !running && <button type="button" className="btn ghost" onClick={reset}><X aria-hidden /> Clear</button>}
          </div>
          {error && <Alert tone="danger" title="Something went wrong">{error}</Alert>}
        </section>

        <aside className="panel">
          <div className="panelHead"><div><p className="eyebrow">Processing</p><h2>{running ? 'Analysis in progress' : job ? statusTitle(job) : 'Ready'}</h2></div></div>
          {running && phase === 'PROCESSING' && (
            <div className="progressBlock">
              <div className="progressBar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={p.percent || 0}>
                <i style={{ width: `${p.percent || 2}%` }} />
              </div>
              <small className="muted">
                {p.framesAnalyzed ? `${p.framesAnalyzed}${p.framesTotal ? ` / ${p.framesTotal}` : ''} frames · ${fmtSeconds(p.videoSeconds)} of ${p.durationSeconds ? fmtSeconds(p.durationSeconds) : 'video'}` : 'Starting…'}
                {p.peopleInFrame ? ` · ${p.peopleInFrame} in frame` : ''}
              </small>
            </div>
          )}
          {phase === 'UPLOADING' && (
            <div className="progressBlock">
              <div className="progressBar" role="progressbar" aria-valuenow={uploadPct} aria-valuemin={0} aria-valuemax={100}><i style={{ width: `${uploadPct}%` }} /></div>
              <small className="muted">Uploading {uploadPct}% of {file && fmtBytes(file.size)}</small>
            </div>
          )}
          <ol className="stageList">
            {STAGES.map(([key, label]) => {
              const { s, note } = states[key];
              const Icon = STAGE_ICON[s];
              return (
                <li key={key} className={`stage ${s}`}>
                  <Icon aria-hidden className={s === 'active' ? 'spin' : ''} />
                  <span>{label}{note && <small>{note}</small>}</span>
                </li>
              );
            })}
          </ol>
          <div className="configSummary">
            <p className="eyebrow">Detection settings</p>
            {cfg ? (
              <dl>
                <div><dt>Resident</dt><dd>{cfg.residentName}{cfg.residentAge ? `, ${cfg.residentAge}` : ''}</dd></div>
                <div><dt>Camera</dt><dd>{cfg.cameraName}</dd></div>
                <div><dt>Verification window</dt><dd>{cfg.verificationSeconds} s</dd></div>
                <div><dt>Confidence threshold</dt><dd>{fmtPercent(cfg.confidenceThreshold)}</dd></div>
              </dl>
            ) : <small className="muted">{settings.error || 'Loading…'}</small>}
            <Link to="/app/settings" className="textLink"><Settings2 aria-hidden /> Change settings</Link>
          </div>
        </aside>
      </div>

      {job && TERMINAL.includes(job.status) && <JobResult job={job} onRetryReport={retryReport} busy={busy} />}
    </>
  );
}

function statusTitle(job) {
  if (job.status === 'FAILED') return 'Analysis failed';
  if (job.status === 'CANCELLED') return 'Analysis cancelled';
  return 'Analysis complete';
}

const OUTCOME = {
  FALL_CONFIRMED: { tone: 'danger', icon: AlertTriangle, title: 'Fall detected and verified' },
  INCONCLUSIVE: { tone: 'warning', icon: Clock3, title: 'Potential fall – inconclusive' },
  NO_FALL: { tone: 'success', icon: ShieldCheck, title: 'No fall detected' },
};

function JobResult({ job, onRetryReport, busy }) {
  if (job.status === 'FAILED') {
    return <section className="panel resultPanel"><Alert tone="danger" title="The video could not be analysed">{job.error || 'Unknown error.'} Choose another file or try again.</Alert></section>;
  }
  if (job.status === 'CANCELLED') {
    return <section className="panel resultPanel"><Alert tone="info" title="Analysis cancelled">No incident was created. You can start the analysis again.</Alert></section>;
  }
  const r = job.result;
  const v = r.verification;
  const o = OUTCOME[r.outcome] || OUTCOME.NO_FALL;
  const Icon = o.icon;
  return (
    <section className="panel resultPanel" aria-live="polite">
      <div className={`outcome ${o.tone}`}>
        <span className="outcomeIcon"><Icon aria-hidden /></span>
        <div><b>{o.title}</b><p>{r.message}</p></div>
        {job.incident && <Link className={`btn ${r.outcome === 'FALL_CONFIRMED' ? 'danger' : 'primary'}`} to={`/app/incidents/${job.incident.id}`}>View incident {job.incident.incidentId}</Link>}
      </div>

      {job.incidentError && (
        <Alert tone="danger" title="The incident could not be saved" action={(
          <button type="button" className="btn outline small" onClick={onRetryReport} disabled={busy}>
            {busy ? <Loader2 className="spin" aria-hidden /> : <RefreshCw aria-hidden />} Retry saving
          </button>
        )}>{job.incidentError}</Alert>
      )}

      <div className="resultGrid">
        <div>
          <p className="eyebrow">Captured frame</p>
          {job.frameAvailable
            ? <AuthImage src={`/detection/jobs/${job.jobId}/frame`} alt="Annotated frame at the detected fall" className="evidenceImage" />
            : <div className="imagePlaceholder evidenceImage"><Video aria-hidden /><span>{r.outcome === 'NO_FALL' && r.verificationStatus === 'NOT_TRIGGERED' ? 'No fall frame: no potential fall was detected.' : 'No frame captured.'}</span></div>}
        </div>
        <div>
          <p className="eyebrow">Detection result</p>
          <dl className="detailList">
            <div><dt>Fall detected</dt><dd>{r.fallDetected ? 'Yes' : 'No'}</dd></div>
            <div><dt>Confidence</dt><dd>{r.outcome === 'NO_FALL' && !v ? '—' : fmtPercent(r.confidence)} <small className="muted">threshold {fmtPercent(r.confidenceThreshold)}</small></dd></div>
            <div><dt>Verification</dt><dd>{(r.verificationStatus || '').replace('_', ' ').toLowerCase()}</dd></div>
            {v && <div><dt>Fall moment</dt><dd>{fmtSeconds(v.fallStartSeconds)} into the video</dd></div>}
            {r.detectedAt && <div><dt>Detected at</dt><dd>{fmtDateTimeLong(r.detectedAt)}</dd></div>}
            {v && <div><dt>Posture</dt><dd>{POSTURE_LABELS[v.postureBefore]} → {POSTURE_LABELS[v.postureAfter]}</dd></div>}
            {v && <div><dt>Observed down</dt><dd>{fmtSeconds(v.observedDownSeconds)} within {fmtSeconds(v.elapsedSeconds)}</dd></div>}
            <div><dt>People detected</dt><dd>{r.maxPeopleDetected}</dd></div>
            <div><dt>Frames analysed</dt><dd>{r.framesAnalyzed} at {r.analysisFps} fps</dd></div>
            <div><dt>Processing time</dt><dd>{(r.processingMs / 1000).toFixed(1)} s</dd></div>
            {job.incident && <div><dt>Incident ID</dt><dd><Link className="textLink" to={`/app/incidents/${job.incident.id}`}>{job.incident.incidentId}</Link></dd></div>}
          </dl>
        </div>
      </div>

      {v?.reasons?.length > 0 && (
        <div className="evidence">
          <p className="eyebrow">Why the system decided this</p>
          <ul>{v.reasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
        </div>
      )}
      {r.events?.length > 0 && (
        <div className="evidence">
          <p className="eyebrow">Timeline</p>
          <ol className="timeline">{r.events.map((e) => <li key={`${e.t}-${e.type}`}><b>{fmtSeconds(e.t)}</b><span>{e.message}</span></li>)}</ol>
        </div>
      )}
    </section>
  );
}

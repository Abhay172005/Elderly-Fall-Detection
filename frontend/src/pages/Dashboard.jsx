import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Activity, AlertTriangle, Bot, CalendarClock, CheckCircle2, CircleAlert, CircleDashed, Database, Mail, Play, RefreshCw, Server, ShieldCheck } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { useApi } from '../hooks';
import { DistributionChart, TrendChart } from '../components/Charts';
import { EmptyState, ErrorState, LoadingBlock, PageHeader, StatusBadge } from '../components/ui';
import { fmtDateTime, fmtPercent, greeting, timeAgo } from '../utils/format';

const RANGES = [7, 14, 30];

export default function Dashboard() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [days, setDays] = useState(14);
  const stats = useApi('/dashboard/stats', { params: { days }, interval: 30000 });
  const health = useApi('/health', { interval: 30000 });
  const s = stats.data;
  const firstName = (user?.fullName || '').split(' ')[0];

  return (
    <>
      <PageHeader
        eyebrow="Overview"
        title={`${greeting()}${firstName ? `, ${firstName}` : ''}.`}
        description={s ? `${s.awaitingAcknowledgement} incident${s.awaitingAcknowledgement === 1 ? '' : 's'} awaiting acknowledgement · updated ${timeAgo(s.generatedAt)}` : 'Loading the latest safety data…'}
        actions={(
          <>
            <button type="button" className="btn outline" onClick={() => { stats.reload(); health.reload(); }}><RefreshCw aria-hidden /> Refresh</button>
            <Link className="btn primary" to="/app/monitoring"><Play aria-hidden /> Analyse a video</Link>
          </>
        )}
      />

      {stats.error && !s ? <ErrorState message={stats.error} onRetry={() => stats.reload()} /> : !s ? <LoadingBlock /> : (
        <>
          <section className="statGrid" aria-label="Incident statistics">
            <StatTile icon={CircleAlert} label="Total incidents" value={s.total} note="All recorded incidents" />
            <StatTile icon={CalendarClock} label="Detected today" value={s.today} note="Since midnight" />
            <StatTile icon={AlertTriangle} tone="critical" label="Verified" value={s.verified} note="Open, fall confirmed" onClick={() => navigate('/app/incidents?status=VERIFIED')} />
            <StatTile icon={CircleDashed} tone="warning" label="Pending review" value={s.pending} note="Inconclusive evidence" onClick={() => navigate('/app/incidents?status=PENDING')} />
            <StatTile icon={CheckCircle2} tone="good" label="Resolved" value={s.resolved} note={`${s.falseAlarm} false alarm${s.falseAlarm === 1 ? '' : 's'}`} onClick={() => navigate('/app/incidents?status=RESOLVED')} />
          </section>

          <div className="dashGrid">
            <section className="panel trendPanel">
              <div className="panelHead">
                <div><p className="eyebrow">Incident trend</p><h2>Incidents per day</h2></div>
                <div className="segmented" role="group" aria-label="Time range">
                  {RANGES.map((r) => <button type="button" key={r} className={r === days ? 'active' : ''} onClick={() => setDays(r)}>{r} days</button>)}
                </div>
              </div>
              <TrendChart points={s.trend} />
            </section>

            <section className="panel">
              <div className="panelHead"><div><p className="eyebrow">Distribution</p><h2>Incidents by status</h2></div></div>
              {s.total ? (
                <>
                  <DistributionChart distribution={s.distribution} onSelect={(st) => navigate(`/app/incidents?status=${st}`)} />
                  <p className="muted small">Average detection confidence: <b>{fmtPercent(s.averageConfidence)}</b></p>
                </>
              ) : <EmptyState icon={ShieldCheck} title="No incidents recorded">Confirmed detections will appear here.</EmptyState>}
            </section>

            <section className="panel">
              <div className="panelHead">
                <div><p className="eyebrow">Recent activity</p><h2>Latest incidents</h2></div>
                <Link to="/app/incidents" className="textLink">View all</Link>
              </div>
              {s.recent.length ? (
                <ul className="activityList">
                  {s.recent.map((i) => (
                    <li key={i.id}>
                      <Link to={`/app/incidents/${i.id}`}>
                        <span className={`activityDot ${i.status.toLowerCase()}`} aria-hidden />
                        <span className="activityText">
                          <b>{i.incidentId}</b>
                          <small>{i.elderlyName} · {i.cameraName} · {fmtDateTime(i.timestamp)}</small>
                        </span>
                        <StatusBadge status={i.status} />
                      </Link>
                    </li>
                  ))}
                </ul>
              ) : <EmptyState icon={Activity} title="All clear">No incidents have been recorded yet.</EmptyState>}
            </section>

            <section className="panel">
              <div className="panelHead"><div><p className="eyebrow">System health</p><h2>Service connectivity</h2></div></div>
              <HealthList health={health} />
            </section>
          </div>
        </>
      )}
    </>
  );
}

function StatTile({ icon: Icon, label, value, note, tone, onClick }) {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag type={onClick ? 'button' : undefined} className={`stat ${tone || ''} ${onClick ? 'clickable' : ''}`} onClick={onClick}>
      <span className="statIcon"><Icon aria-hidden /></span>
      <span className="statBody">
        <span className="statLabel">{label}</span>
        <span className="statValue">{value.toLocaleString()}</span>
        <span className="statNote">{note}</span>
      </span>
    </Tag>
  );
}

const HEALTH_ROWS = [
  ['backend', 'Backend API', Server],
  ['database', 'MongoDB database', Database],
  ['mlService', 'ML service (YOLO Pose)', Activity],
  ['ai', 'AI summaries (Groq)', Bot],
  ['email', 'Email notifications', Mail],
];

export function HealthList({ health }) {
  if (health.loading && !health.data) return <LoadingBlock label="Checking services…" />;
  if (health.error && !health.data) {
    return (
      <ul className="healthList">
        <li><Server aria-hidden /><span><b>Backend API</b><small>{health.error}</small></span><span className="pill bad">Down</span></li>
      </ul>
    );
  }
  const h = health.data;
  return (
    <ul className="healthList">
      {HEALTH_ROWS.map(([key, label, Icon]) => {
        const c = h[key] || {};
        const tone = c.status === 'UP' || c.status === 'CONFIGURED' ? 'ok' : c.status === 'DEGRADED' ? 'warn' : c.status === 'NOT_CONFIGURED' ? 'muted' : 'bad';
        const text = { UP: 'Online', CONFIGURED: 'Configured', DEGRADED: 'Degraded', NOT_CONFIGURED: 'Not configured', DOWN: 'Offline' }[c.status] || c.status;
        return (
          <li key={key}>
            <Icon aria-hidden />
            <span><b>{label}</b><small>{c.detail}{c.latencyMs != null ? ` · ${c.latencyMs} ms` : ''}</small></span>
            <span className={`pill ${tone}`}>{text}</span>
          </li>
        );
      })}
    </ul>
  );
}

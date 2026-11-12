import { useMemo, useState } from 'react';
import { AlertTriangle, CheckCircle2, CircleDashed, CircleSlash } from 'lucide-react';
import { fmtDay, STATUS_LABELS } from '../utils/format';

/**
 * Daily incident counts as a single-series column chart.
 * One hue, thin columns with a 4px rounded data-end, hairline grid, per-column hover tooltip, table view toggle.
 */
export function TrendChart({ points }) {
  const [hover, setHover] = useState(null);
  const [asTable, setAsTable] = useState(false);
  const width = 640;
  const height = 210;
  const pad = { top: 16, right: 12, bottom: 28, left: 32 };
  const max = Math.max(1, ...points.map((p) => p.count));
  const niceMax = max <= 4 ? max : Math.ceil(max / 4) * 4;
  const ticks = niceMax <= 4 ? Array.from({ length: niceMax + 1 }, (_, i) => i) : [0, niceMax / 4, niceMax / 2, (3 * niceMax) / 4, niceMax];
  const plotW = width - pad.left - pad.right;
  const plotH = height - pad.top - pad.bottom;
  const band = plotW / points.length;
  const barW = Math.min(24, band * 0.62);
  const y = (v) => pad.top + plotH - (v / niceMax) * plotH;
  const labelEvery = points.length > 16 ? 5 : points.length > 10 ? 2 : 1;
  const total = points.reduce((s, p) => s + p.count, 0);

  return (
    <div className="chart">
      <div className="chartToolbar">
        <span className="muted small">{total} incident{total === 1 ? '' : 's'} in the last {points.length} days</span>
        <button type="button" className="linkBtn" onClick={() => setAsTable((v) => !v)}>{asTable ? 'Show chart' : 'View as table'}</button>
      </div>
      {asTable ? (
        <div className="tableWrap compact">
          <table>
            <thead><tr><th>Date</th><th className="num">Incidents</th></tr></thead>
            <tbody>{points.map((p) => <tr key={p.date}><td>{fmtDay(p.date)}</td><td className="num">{p.count}</td></tr>)}</tbody>
          </table>
        </div>
      ) : (
        <div className="chartCanvas">
          <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`Daily incidents, ${total} in total`} onMouseLeave={() => setHover(null)}>
            {ticks.map((t) => (
              <g key={t}>
                <line x1={pad.left} x2={width - pad.right} y1={y(t)} y2={y(t)} className="gridLine" />
                <text x={pad.left - 8} y={y(t) + 4} textAnchor="end" className="axisText">{t}</text>
              </g>
            ))}
            {points.map((p, i) => {
              const cx = pad.left + band * i + band / 2;
              const h = (p.count / niceMax) * plotH;
              const r = Math.min(4, h / 2);
              const x0 = cx - barW / 2;
              const yTop = pad.top + plotH - h;
              const path = h > 0
                ? `M${x0},${pad.top + plotH} V${yTop + r} Q${x0},${yTop} ${x0 + r},${yTop} H${x0 + barW - r} Q${x0 + barW},${yTop} ${x0 + barW},${yTop + r} V${pad.top + plotH} Z`
                : null;
              return (
                <g key={p.date}>
                  {hover === i && <rect x={pad.left + band * i} y={pad.top} width={band} height={plotH} className="hoverBand" />}
                  {path && <path d={path} className="bar" />}
                  <rect x={pad.left + band * i} y={pad.top} width={band} height={plotH + pad.bottom} fill="transparent"
                        onMouseEnter={() => setHover(i)} onFocus={() => setHover(i)} tabIndex={0} aria-label={`${fmtDay(p.date)}: ${p.count} incidents`} />
                  {i % labelEvery === 0 && <text x={cx} y={height - 8} textAnchor="middle" className="axisText">{fmtDay(p.date)}</text>}
                </g>
              );
            })}
            <line x1={pad.left} x2={width - pad.right} y1={pad.top + plotH} y2={pad.top + plotH} className="baseLine" />
          </svg>
          {hover !== null && (
            <div className="chartTooltip" style={{ left: `${((pad.left + band * hover + band / 2) / width) * 100}%` }}>
              <b>{points[hover].count}</b> incident{points[hover].count === 1 ? '' : 's'}
              <span>{fmtDay(points[hover].date)}</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

const STATUS_ORDER = ['VERIFIED', 'PENDING', 'RESOLVED', 'FALSE_ALARM'];
const STATUS_ICON = { VERIFIED: AlertTriangle, PENDING: CircleDashed, RESOLVED: CheckCircle2, FALSE_ALARM: CircleSlash };

/** Status distribution as labelled horizontal bars: status colour plus icon and text, never colour alone. */
export function DistributionChart({ distribution, onSelect }) {
  const rows = useMemo(() => STATUS_ORDER.map((s) => ({ status: s, count: distribution?.[s] || 0 })), [distribution]);
  const total = rows.reduce((s, r) => s + r.count, 0);
  const max = Math.max(1, ...rows.map((r) => r.count));
  return (
    <div className="distribution" role="list">
      {rows.map(({ status, count }) => {
        const Icon = STATUS_ICON[status];
        const share = total ? Math.round((count / total) * 100) : 0;
        return (
          <button type="button" role="listitem" key={status} className="distRow" onClick={() => onSelect?.(status)}
                  title={`${STATUS_LABELS[status]}: ${count} (${share}%)`}>
            <span className="distLabel"><Icon aria-hidden className={`ico-${status.toLowerCase()}`} />{STATUS_LABELS[status]}</span>
            <span className="distTrack">
              <span className={`distBar bar-${status.toLowerCase()}`} style={{ width: count ? `${Math.max(2, (count / max) * 100)}%` : 0 }} />
            </span>
            <span className="distValue">{count}<small>{share}%</small></span>
          </button>
        );
      })}
    </div>
  );
}

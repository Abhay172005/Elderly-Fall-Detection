import { useEffect, useState } from 'react';
import { Bot, Loader2, Lock, Mail, Save } from 'lucide-react';
import { api, errorText, fieldErrors } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useApi } from '../hooks';
import { Alert, ErrorState, Field, LoadingBlock, PageHeader } from '../components/ui';
import { fmtDateTimeLong } from '../utils/format';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function Settings() {
  const { isAdmin } = useAuth();
  const settings = useApi('/settings');
  const [form, setForm] = useState(null);
  const [errors, setErrors] = useState({});
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState(null);

  useEffect(() => {
    if (settings.data) setForm({ ...settings.data, residentAge: settings.data.residentAge ?? '', cameraLocation: settings.data.cameraLocation ?? '', emergencyEmail: settings.data.emergencyEmail ?? '' });
  }, [settings.data]);

  if (settings.error && !settings.data) return <ErrorState message={settings.error} onRetry={() => settings.reload()} />;
  if (!form) return <LoadingBlock label="Loading settings…" />;

  const set = (key) => (e) => setForm({ ...form, [key]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });
  const dirty = JSON.stringify(normalize(form)) !== JSON.stringify(normalize({ ...settings.data, residentAge: settings.data.residentAge ?? '', cameraLocation: settings.data.cameraLocation ?? '', emergencyEmail: settings.data.emergencyEmail ?? '' }));

  function validate() {
    const e = {};
    const threshold = Number(form.confidenceThreshold);
    if (!(threshold >= 0.3 && threshold <= 0.99)) e.confidenceThreshold = 'Between 0.30 and 0.99';
    const secs = Number(form.verificationSeconds);
    if (!Number.isInteger(secs) || secs < 2 || secs > 30) e.verificationSeconds = 'Whole seconds between 2 and 30';
    if (!form.residentName.trim()) e.residentName = 'Resident name is required';
    if (form.residentAge !== '' && !(Number(form.residentAge) >= 40 && Number(form.residentAge) <= 130)) e.residentAge = 'Age must be 40–130';
    if (!form.cameraName.trim()) e.cameraName = 'Camera name is required';
    if (form.emergencyEmail.trim() && !EMAIL_RE.test(form.emergencyEmail.trim())) e.emergencyEmail = 'Enter a valid email address';
    return e;
  }

  async function save(e) {
    e.preventDefault();
    const v = validate();
    setErrors(v);
    setMessage(null);
    if (Object.keys(v).length) return;
    setSaving(true);
    try {
      const { data } = await api.put('/settings', normalize(form));
      settings.setData(data);
      setMessage({ tone: 'success', text: 'Settings saved. New analyses will use them immediately.' });
    } catch (err) {
      setErrors(fieldErrors(err));
      setMessage({ tone: 'danger', text: errorText(err, 'Settings could not be saved.') });
    } finally {
      setSaving(false);
    }
  }

  const ro = !isAdmin;
  const integ = form.integrations || {};
  return (
    <form onSubmit={save} noValidate>
      <PageHeader eyebrow="Configuration" title="Settings"
                  description={form.updatedAt ? `Last updated ${fmtDateTimeLong(form.updatedAt)}${form.updatedBy ? ` by ${form.updatedBy}` : ''}` : 'Default settings are in use.'}
                  actions={isAdmin && <button className="btn primary" disabled={saving || !dirty}>{saving ? <Loader2 className="spin" aria-hidden /> : <Save aria-hidden />} Save changes</button>} />
      {ro && <Alert tone="info" title="Read only"><Lock aria-hidden className="inlineIcon" /> Only administrators can change settings.</Alert>}
      {message && <Alert tone={message.tone}>{message.text}</Alert>}

      <div className="settingsGrid">
        <section className="panel">
          <p className="eyebrow">Fall detection</p>
          <h2>Detection sensitivity</h2>
          <Field label={`Confidence threshold: ${Math.round(Number(form.confidenceThreshold) * 100)}%`} htmlFor="threshold" error={errors.confidenceThreshold}
                 hint="Confirmed falls below this confidence are recorded as Pending review instead of Verified.">
            <input id="threshold" type="range" min="0.3" max="0.99" step="0.01" value={form.confidenceThreshold} onChange={set('confidenceThreshold')} disabled={ro} />
          </Field>
          <Field label="Verification period (seconds)" htmlFor="verification" error={errors.verificationSeconds}
                 hint="How long a person must stay down after a potential fall before it is confirmed.">
            <input id="verification" type="number" min="2" max="30" step="1" value={form.verificationSeconds} onChange={set('verificationSeconds')} disabled={ro} />
          </Field>
          <label className="toggle">
            <input type="checkbox" checked={form.recordInconclusive} onChange={set('recordInconclusive')} disabled={ro} />
            <span><b>Record inconclusive detections</b><small>Store potential falls that could not be verified (e.g. video ended, person hidden) as Pending review.</small></span>
          </label>
        </section>

        <section className="panel">
          <p className="eyebrow">Resident</p>
          <h2>Resident information</h2>
          <Field label="Resident name" htmlFor="residentName" error={errors.residentName}>
            <input id="residentName" maxLength={80} value={form.residentName} onChange={set('residentName')} disabled={ro} />
          </Field>
          <Field label="Age" htmlFor="residentAge" error={errors.residentAge} hint="Optional">
            <input id="residentAge" type="number" min="40" max="130" value={form.residentAge} onChange={set('residentAge')} disabled={ro} />
          </Field>
          <p className="eyebrow spaced">Camera</p>
          <Field label="Camera name" htmlFor="cameraName" error={errors.cameraName} hint="Recorded on every incident from this camera.">
            <input id="cameraName" maxLength={80} value={form.cameraName} onChange={set('cameraName')} disabled={ro} />
          </Field>
          <Field label="Location" htmlFor="cameraLocation" error={errors.cameraLocation} hint="Optional, e.g. Ground floor, Room 4">
            <input id="cameraLocation" maxLength={120} value={form.cameraLocation} onChange={set('cameraLocation')} disabled={ro} />
          </Field>
        </section>

        <section className="panel">
          <p className="eyebrow">Notifications</p>
          <h2>Emergency alerts</h2>
          <Field label="Emergency email" htmlFor="emergencyEmail" error={errors.emergencyEmail}
                 hint={integ.fallbackEmergencyEmailConfigured ? 'Leave empty to use EMERGENCY_EMAIL from the server configuration.' : 'Where fall alerts are sent.'}>
            <input id="emergencyEmail" type="email" maxLength={120} value={form.emergencyEmail} onChange={set('emergencyEmail')} disabled={ro} placeholder="caregiver@example.com" />
          </Field>
          <label className="toggle">
            <input type="checkbox" checked={form.emailNotificationsEnabled} onChange={set('emailNotificationsEnabled')} disabled={ro} />
            <span><b>Email verified falls</b><small>Send an alert as soon as a verified incident is stored.</small></span>
          </label>
          <label className="toggle">
            <input type="checkbox" checked={form.notifyOnPending} onChange={set('notifyOnPending')} disabled={ro} />
            <span><b>Email pending incidents too</b><small>Also alert for inconclusive detections that need review.</small></span>
          </label>
          <label className="toggle">
            <input type="checkbox" checked={form.attachPdfToEmail} onChange={set('attachPdfToEmail')} disabled={ro} />
            <span><b>Attach PDF report</b><small>Include the incident report in the alert email.</small></span>
          </label>
        </section>

        <section className="panel">
          <p className="eyebrow">Integrations</p>
          <h2>Server configuration</h2>
          <p className="muted small">Configured through environment variables on the server; shown here for reference.</p>
          <ul className="healthList">
            <li><Bot aria-hidden /><span><b>AI summaries (Groq)</b><small>{integ.groqConfigured ? `Model ${integ.groqModel}` : 'GROQ_API_KEY not set: template summaries are used.'}</small></span>
              <span className={`pill ${integ.groqConfigured ? 'ok' : 'muted'}`}>{integ.groqConfigured ? 'Configured' : 'Not configured'}</span></li>
            <li><Mail aria-hidden /><span><b>SMTP email</b><small>{integ.smtpConfigured ? 'Credentials present.' : 'MAIL_USERNAME / MAIL_PASSWORD not set: emails are not sent.'}</small></span>
              <span className={`pill ${integ.smtpConfigured ? 'ok' : 'muted'}`}>{integ.smtpConfigured ? 'Configured' : 'Not configured'}</span></li>
          </ul>
        </section>
      </div>
    </form>
  );
}

function normalize(f) {
  return {
    confidenceThreshold: Number(f.confidenceThreshold),
    verificationSeconds: Number(f.verificationSeconds),
    residentName: f.residentName.trim(),
    residentAge: f.residentAge === '' || f.residentAge === null ? null : Number(f.residentAge),
    cameraName: f.cameraName.trim(),
    cameraLocation: (f.cameraLocation || '').trim() || null,
    emergencyEmail: (f.emergencyEmail || '').trim() || null,
    emailNotificationsEnabled: Boolean(f.emailNotificationsEnabled),
    attachPdfToEmail: Boolean(f.attachPdfToEmail),
    notifyOnPending: Boolean(f.notifyOnPending),
    recordInconclusive: Boolean(f.recordInconclusive),
  };
}

import { useEffect, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ChevronRight, Eye, EyeOff, Loader2, ShieldCheck } from 'lucide-react';
import { api, errorText, fieldErrors } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { Alert, Field, Logo } from '../components/ui';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function AuthShell({ children }) {
  return (
    <div className="authPage">
      <section className="authIntro">
        <Link to="/"><Logo light /></Link>
        <div>
          <p className="eyebrow light">Safety, always on</p>
          <h1>Care begins with awareness.</h1>
          <p>Review verified fall incidents, respond with confidence, and keep every action documented.</p>
        </div>
        <p className="authNote"><ShieldCheck aria-hidden /> Accounts are protected with hashed passwords and signed session tokens.</p>
      </section>
      <section className="authFormWrap">
        <Link className="back" to="/">← Back to home</Link>
        {children}
      </section>
    </div>
  );
}

function PasswordInput({ id, value, onChange, autoComplete, invalid }) {
  const [show, setShow] = useState(false);
  return (
    <div className="passwordInput">
      <input id={id} type={show ? 'text' : 'password'} value={value} onChange={onChange} autoComplete={autoComplete}
             aria-invalid={invalid || undefined} />
      <button type="button" className="iconBtn" onClick={() => setShow((s) => !s)} aria-label={show ? 'Hide password' : 'Show password'}>
        {show ? <EyeOff /> : <Eye />}
      </button>
    </div>
  );
}

export function Login() {
  const { login, isAuthenticated, expired } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [form, setForm] = useState({ email: '', password: '' });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [registration, setRegistration] = useState(null);

  useEffect(() => {
    api.get('/auth/registration-status').then(({ data }) => setRegistration(data)).catch(() => setRegistration(null));
  }, []);

  if (isAuthenticated) return <Navigate to={location.state?.from || '/app/dashboard'} replace />;

  async function submit(e) {
    e.preventDefault();
    const next = {};
    if (!EMAIL_RE.test(form.email.trim())) next.email = 'Enter a valid email address';
    if (!form.password) next.password = 'Password is required';
    setErrors(next);
    if (Object.keys(next).length) return;
    setBusy(true);
    setError('');
    try {
      await login(form.email.trim(), form.password);
      navigate(location.state?.from || '/app/dashboard', { replace: true });
    } catch (err) {
      setErrors(fieldErrors(err));
      setError(errorText(err, 'Sign-in failed.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthShell>
      <form className="authForm" onSubmit={submit} noValidate>
        <p className="eyebrow">Secure access</p>
        <h2>Welcome back</h2>
        <p className="muted">Sign in to your caregiver dashboard.</p>
        {expired && !error && <Alert tone="warning">Your session has expired. Please sign in again.</Alert>}
        {error && <Alert tone="danger">{error}</Alert>}
        {registration?.firstUser && (
          <Alert tone="info" title="No accounts yet">Create the first account; it becomes the administrator. <Link to="/register">Create account</Link></Alert>
        )}
        <Field label="Email" htmlFor="email" error={errors.email}>
          <input id="email" type="email" autoComplete="email" value={form.email} aria-invalid={Boolean(errors.email) || undefined}
                 onChange={(e) => setForm({ ...form, email: e.target.value })} />
        </Field>
        <Field label="Password" htmlFor="password" error={errors.password}>
          <PasswordInput id="password" autoComplete="current-password" value={form.password} invalid={Boolean(errors.password)}
                         onChange={(e) => setForm({ ...form, password: e.target.value })} />
        </Field>
        <button className="btn primary wide large" disabled={busy}>
          {busy ? <><Loader2 className="spin" aria-hidden /> Signing in…</> : <>Sign in <ChevronRight aria-hidden /></>}
        </button>
        {registration?.registrationOpen !== false && (
          <p className="switchAuth">New to the portal? <Link to="/register">Create an account</Link></p>
        )}
      </form>
    </AuthShell>
  );
}

export function Register() {
  const { register, isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ fullName: '', email: '', password: '', confirm: '' });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [registration, setRegistration] = useState(null);

  useEffect(() => {
    api.get('/auth/registration-status').then(({ data }) => setRegistration(data)).catch(() => setRegistration(null));
  }, []);

  if (isAuthenticated) return <Navigate to="/app/dashboard" replace />;

  function validate() {
    const next = {};
    if (form.fullName.trim().length < 2) next.fullName = 'Enter your full name';
    if (!EMAIL_RE.test(form.email.trim())) next.email = 'Enter a valid email address';
    if (form.password.length < 8) next.password = 'At least 8 characters';
    else if (!/[A-Za-z]/.test(form.password) || !/\d/.test(form.password)) next.password = 'Use letters and numbers';
    if (form.confirm !== form.password) next.confirm = 'Passwords do not match';
    return next;
  }

  async function submit(e) {
    e.preventDefault();
    const next = validate();
    setErrors(next);
    if (Object.keys(next).length) return;
    setBusy(true);
    setError('');
    try {
      await register(form.fullName.trim(), form.email.trim(), form.password);
      navigate('/app/dashboard', { replace: true });
    } catch (err) {
      setErrors(fieldErrors(err));
      setError(errorText(err, 'Registration failed.'));
    } finally {
      setBusy(false);
    }
  }

  const closed = registration && !registration.registrationOpen;
  return (
    <AuthShell>
      <form className="authForm" onSubmit={submit} noValidate>
        <p className="eyebrow">Create account</p>
        <h2>Join your care team</h2>
        <p className="muted">{registration?.firstUser
          ? 'You are creating the first account, which will have administrator rights.'
          : 'New accounts get caregiver access. Administrators manage settings and deletions.'}</p>
        {closed && <Alert tone="warning">Self-registration is disabled. Ask an administrator to create an account for you.</Alert>}
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label="Full name" htmlFor="fullName" error={errors.fullName}>
          <input id="fullName" autoComplete="name" value={form.fullName} disabled={closed}
                 onChange={(e) => setForm({ ...form, fullName: e.target.value })} />
        </Field>
        <Field label="Email" htmlFor="regEmail" error={errors.email}>
          <input id="regEmail" type="email" autoComplete="email" value={form.email} disabled={closed}
                 onChange={(e) => setForm({ ...form, email: e.target.value })} />
        </Field>
        <Field label="Password" htmlFor="regPassword" error={errors.password} hint="At least 8 characters, letters and numbers">
          <PasswordInput id="regPassword" autoComplete="new-password" value={form.password} invalid={Boolean(errors.password)}
                         onChange={(e) => setForm({ ...form, password: e.target.value })} />
        </Field>
        <Field label="Confirm password" htmlFor="confirm" error={errors.confirm}>
          <PasswordInput id="confirm" autoComplete="new-password" value={form.confirm} invalid={Boolean(errors.confirm)}
                         onChange={(e) => setForm({ ...form, confirm: e.target.value })} />
        </Field>
        <button className="btn primary wide large" disabled={busy || closed}>
          {busy ? <><Loader2 className="spin" aria-hidden /> Creating account…</> : <>Create account <ChevronRight aria-hidden /></>}
        </button>
        <p className="switchAuth">Already have an account? <Link to="/login">Sign in</Link></p>
      </form>
    </AuthShell>
  );
}

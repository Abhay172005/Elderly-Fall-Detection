import { Link } from 'react-router-dom';
import { Activity, BellRing, Brain, Camera, ChevronRight, Clock3, Database, FileText, ScanLine, Server, ShieldCheck, Timer, Users } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { Logo } from '../components/ui';

const FEATURES = [
  [ScanLine, 'Pose-based fall detection', 'YOLO Pose tracks 17 body keypoints per person and classifies posture frame by frame.'],
  [Timer, 'Temporal verification', 'A potential fall is only confirmed if the person stays down for the verification period.'],
  [Brain, 'AI incident summaries', 'A language model writes a factual summary and next steps only after a fall is confirmed.'],
  [BellRing, 'Caregiver alerts', 'Email alerts with the PDF report attached, and a clear acknowledge-and-resolve workflow.'],
  [FileText, 'Professional reports', 'Every incident gets a downloadable PDF with the evidence frame and response timeline.'],
  [ShieldCheck, 'Secure by design', 'Authenticated portal, hashed passwords, role-based actions and no hard-coded secrets.'],
];

const STEPS = [
  ['01', 'Upload', 'A caregiver uploads footage from a monitoring camera.'],
  ['02', 'Analyse', 'OpenCV samples frames and YOLO Pose estimates each person’s posture.'],
  ['03', 'Detect', 'A rapid transition from upright to the floor raises a potential fall.'],
  ['04', 'Verify', 'The person must remain down for the verification window, otherwise it is cancelled.'],
  ['05', 'Respond', 'The incident is stored, summarised, reported and sent to caregivers.'],
];

const TECH = [
  [Camera, 'Computer vision', 'Python · FastAPI · OpenCV · Ultralytics YOLO Pose'],
  [Server, 'Backend', 'Java · Spring Boot · Spring Security (JWT) · Spring Mail · OpenPDF'],
  [Database, 'Data', 'MongoDB Atlas: incidents, users and settings'],
  [Brain, 'Language model', 'Qwen on Groq, used only after confirmation, with a safe fallback'],
];

export default function Home() {
  const { isAuthenticated } = useAuth();
  const cta = isAuthenticated ? '/app/dashboard' : '/login';
  return (
    <div className="home">
      <header className="homeNav">
        <Link to="/" aria-label="Home"><Logo /></Link>
        <nav aria-label="Sections">
          <a href="#problem">The problem</a>
          <a href="#features">Features</a>
          <a href="#how">How it works</a>
          <a href="#technology">Technology</a>
        </nav>
        <div className="homeNavActions">
          {isAuthenticated ? <Link className="btn primary" to="/app/dashboard">Open portal</Link> : (
            <>
              <Link className="btn ghost" to="/login">Sign in</Link>
              <Link className="btn primary" to="/register">Create account</Link>
            </>
          )}
        </div>
      </header>

      <main>
        <section className="hero">
          <div className="heroText">
            <p className="heroKicker">AI-assisted elderly safety</p>
            <h1>Protection that notices.<br /><span>Response that matters.</span></h1>
            <p className="lead">Video-based fall detection that verifies what it sees before raising an alarm, then gives caregivers
              everything they need to respond: evidence, a clear summary and a report.</p>
            <div className="actions">
              <Link className="btn primary large" to={cta}>Start monitoring <ChevronRight aria-hidden /></Link>
              <a className="btn outline large" href="#how">See how it works</a>
            </div>
            <ul className="trust">
              <li><ShieldCheck aria-hidden /> Verified before alerting</li>
              <li><Clock3 aria-hidden /> Configurable verification window</li>
              <li><Users aria-hidden /> Built for care teams</li>
            </ul>
          </div>
        </section>

        <section id="problem" className="section problem">
          <div>
            <p className="eyebrow">The problem</p>
            <h2>A fall is most dangerous when nobody notices it.</h2>
          </div>
          <div className="problemText">
            <p>Falls are a leading cause of injury among older adults. The time spent on the floor before help arrives matters,
              and continuous human observation is rarely possible in homes or care facilities.</p>
            <p>Simple motion alarms trigger on every sit-down. This portal combines pose estimation with temporal verification
              so caregivers are alerted to falls that persist, and every alert arrives with the evidence behind it.</p>
          </div>
        </section>

        <section id="features" className="section">
          <p className="eyebrow">Features</p>
          <h2>Everything a care team needs, from detection to documentation.</h2>
          <div className="featureGrid">
            {FEATURES.map(([Icon, title, text]) => (
              <article className="feature" key={title}>
                <span className="featureIcon"><Icon aria-hidden /></span>
                <h3>{title}</h3>
                <p>{text}</p>
              </article>
            ))}
          </div>
        </section>

        <section id="how" className="section how">
          <p className="eyebrow">How it works</p>
          <h2>From footage to a verified, documented response.</h2>
          <ol className="steps">
            {STEPS.map(([n, title, text]) => (
              <li className="step" key={n}><b>{n}</b><h3>{title}</h3><p>{text}</p></li>
            ))}
          </ol>
        </section>

        <section id="technology" className="section">
          <p className="eyebrow">Technology</p>
          <h2>Three services, one workflow.</h2>
          <div className="techGrid">
            {TECH.map(([Icon, title, text]) => (
              <div className="techCard" key={title}><Icon aria-hidden /><div><b>{title}</b><p>{text}</p></div></div>
            ))}
          </div>
        </section>

        <section className="cta">
          <h2>Bring verified fall detection to your care team.</h2>
          <p>Sign in to upload footage, review incidents and manage alerts.</p>
          <Link className="btn light large" to={cta}>{isAuthenticated ? 'Open the portal' : 'Get started'} <ChevronRight aria-hidden /></Link>
        </section>
      </main>

      <footer className="footer">
        <Logo />
        <p>Smarter Monitoring. Faster Response. Safer Lives.</p>
        <p className="muted small">Automated detection supports, and does not replace, human supervision and medical judgement.</p>
      </footer>
    </div>
  );
}

function HeroIllustration() {
  return (
    <div className="heroVisual" aria-hidden>
      <div className="hvTop"><span><Camera /> Living room camera</span><span className="hvTag">Illustration</span></div>
      <svg viewBox="0 0 400 260" className="hvScene">
        <rect x="20" y="214" width="360" height="2" rx="1" className="hvFloor" />
        {/* standing figure: skeleton of keypoints */}
        <g className="hvSkeleton">
          <line x1="120" y1="78" x2="120" y2="140" /><line x1="104" y1="86" x2="136" y2="86" />
          <line x1="104" y1="86" x2="96" y2="118" /><line x1="136" y1="86" x2="146" y2="118" />
          <line x1="110" y1="140" x2="130" y2="140" /><line x1="110" y1="140" x2="106" y2="178" /><line x1="130" y1="140" x2="134" y2="178" />
          <line x1="106" y1="178" x2="104" y2="212" /><line x1="134" y1="178" x2="138" y2="212" />
          {[[120, 64], [104, 86], [136, 86], [96, 118], [146, 118], [110, 140], [130, 140], [106, 178], [134, 178], [104, 212], [138, 212]].map(([x, y]) => <circle key={`${x}-${y}`} cx={x} cy={y} r="4" />)}
        </g>
        <rect x="84" y="52" width="72" height="166" rx="6" className="hvBox ok" />
        {/* fallen figure */}
        <g className="hvSkeleton fallen">
          <line x1="250" y1="200" x2="310" y2="204" /><line x1="256" y1="190" x2="258" y2="212" />
          <line x1="310" y1="198" x2="310" y2="210" /><line x1="310" y1="198" x2="345" y2="196" /><line x1="310" y1="210" x2="346" y2="208" />
          {[[236, 200], [256, 190], [258, 212], [310, 198], [310, 210], [345, 196], [346, 208]].map(([x, y]) => <circle key={`${x}-${y}`} cx={x} cy={y} r="4" />)}
        </g>
        <rect x="224" y="178" width="134" height="42" rx="6" className="hvBox fallen" />
      </svg>
      <div className="hvCards">
        <div className="hvCard"><span className="dot ok" /> Standing <small>posture tracked</small></div>
        <div className="hvCard"><span className="dot danger" /> Potential fall <small>verifying…</small></div>
      </div>
    </div>
  );
}

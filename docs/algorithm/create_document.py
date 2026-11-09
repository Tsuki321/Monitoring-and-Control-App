"""Generate the thesis-style algorithm explanation without compiling the Android app."""
from pathlib import Path
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
ROOT.mkdir(parents=True, exist_ok=True)
doc = Document()
section = doc.sections[0]
section.top_margin = section.bottom_margin = Inches(.8)
section.left_margin = section.right_margin = Inches(.85)
normal = doc.styles['Normal']
normal.font.name = 'Calibri'
normal.font.size = Pt(11)
normal.paragraph_format.space_after = Pt(7)
for name in ['Title', 'Heading 1', 'Heading 2']:
    doc.styles[name].font.name = 'Calibri'
    doc.styles[name].font.color.rgb = RGBColor.from_string('0D7A8A')
footer = section.footer.paragraphs[0]
footer.alignment = 2
footer.add_run('HydroSense | Algorithm documentation | ')
field = OxmlElement('w:fldSimple'); field.set(qn('w:instr'), 'PAGE'); footer._p.append(field)

def p(text): doc.add_paragraph(text)
def heading(text): doc.add_heading(text, level=1)
def table(headers, rows):
    t = doc.add_table(rows=1, cols=len(headers)); t.style = 'Light Shading Accent 1'
    for c, s in zip(t.rows[0].cells, headers): c.text = s
    for row in rows:
        for c, s in zip(t.add_row().cells, row): c.text = str(s)
    for row in t.rows:
        for cell in row.cells:
            for paragraph in cell.paragraphs:
                paragraph.paragraph_format.space_after = Pt(4)

doc.add_heading('HydroSense', 0)
doc.add_heading('Machine-Learning Algorithm for Filter Lifespan Estimation', 1)
p('Technical explanation and monthly data-processing design')
p('Prepared for the post-defense application revisions • November 9, 2026')
p('Implementation reference: ridge-monthly-v1. This document describes the existing Kotlin model and the revised monthly backend in this repository. Backend deployment, field validation, and rollout remain separate from code preparation. No experimental accuracy result is claimed.')
heading('1. Purpose and scope')
p('The application estimates when each stage of the five-stage filtration assembly will require service. It learns a water-quality-dependent wear multiplier and combines that multiplier with measured Pump B operating time and a configured calendar limit. Pump A is an intake pump and does not drive filter wear. The published lifespan is an estimated service date, not a measurement of remaining media capacity or a guarantee of water safety.')
p('The learning algorithm is supervised, ridge-regularized multiple linear regression. At startup it fits generated examples from a nonlinear wear model. After sufficient qualifying field observations are available, those observations receive greater weight in fitting. Raw pH, TDS, and turbidity readings alone are not observed lifespan labels.')
heading('2. Inputs and recorded information')
table(['Input', 'Meaning and use'], [
('pH', 'Measured acidity/alkalinity; transformed into absolute deviation from pH 7.'),
('TDS, ppm', 'Measured dissolved solids; normalized by 1,000 ppm and bounded to [0,1].'),
('Turbidity, NTU', 'Measured turbidity; normalized by 3,000 NTU. Values ≥2,999 NTU are treated as a fault for prediction eligibility.'),
('Pump B counter, seconds', 'Cumulative measured energized time. Nonnegative differences provide operating hours.'),
('Source event timestamp and ID', 'Backend-assigned event time and stable event identifier; shared across phones.'),
('Installation and maintenance', 'Stage, revision, action, time, actor, reason, and observed condition.'),
('Tank, pump, control and alert events', 'Historical analytics and operational context; these are not independent lifespan training labels.')])
heading('3. Configured filter references')
table(['Stage', 'Operating hours', 'Calendar days', 'Routine service'], [
('Pumice',15000,1825,'Rinse'),('Pebbles',30000,3650,'Rinse'),('Lava rock',30000,3650,'Rinse'),
('Activated carbon',350,42,'Replace'),('Multilayer foam',1500,180,'Rinse')])
p('These values are configured reference assumptions inherited from FilterSpecs.kt. They are not claimed to be measured field lifetimes. Calendar exhaustion can require replacement even for a normally rinsable stage. First telemetry establishes a commissioning reference; actual new installation should be recorded before collecting a complete field interval.')
heading('4. Feature engineering and regression')
p('For one reading, the feature vector is:')
p('x = [1, clamp(TDS / 1000, 0, 1), clamp(turbidity / 3000, 0, 1), clamp(|pH − 7|, 0, 7)].')
p('The fitted wear multiplier is L = clamp(b₀ + b₁x₁ + b₂x₂ + b₃x₃, 0.25, 4.0). The leading 1 is the intercept feature. Each stage has its own coefficient vector b.')
p('The objective is weighted least squares with L2 regularization: minimize Σᵢ wᵢ(yᵢ − xᵢᵀb)² + λΣⱼbⱼ², where λ = 0.01. The implementation regularizes every coefficient, including the intercept.')
p('The solution satisfies (XᵀWX + λI)b = XᵀWy. The code constructs this four-by-four system and solves it using Gaussian elimination with partial pivoting, followed by back substitution. Nonfinite results and a near-zero pivot are rejected. Ridge regularization limits instability when water-quality features are correlated.')
heading('5. Generated baseline training data')
p('The baseline contains 400 generated examples per stage. A fixed 64-bit linear congruential generator makes the examples reproducible. TDS spans 40–900 ppm with a lower-value skew; turbidity spans 0.5–600 NTU with a lower-value skew. Most pH samples cluster around 7, with a minority sampled across 5–9.5. Heteroscedastic Gaussian noise is added: its standard deviation is 0.03 + 0.12 times the underlying wear factor.')
p('Relative contaminant load is r = (sₜ·turbidity/50 + s_d·TDS/300)/(sₜ+s_d), using stage-specific sensitivities. The generated target is a nonlinear profile multiplied by 1 + 0.28|pH−7|^1.6 and bounded to [0.25,4].')
table(['Profile', 'Baseline relationship'], [
('Particulate media','Normalized resistance K(r)/K(1), with K=(1−ε)²/ε³ and ε=0.5[1−clamp(0.18r,0,0.9)], lower bounded at 0.02.'),
('Activated carbon','max(r,0.001)^(1−1/2.2), representing a modeled adsorption relationship.'),
('Multilayer foam','0.65r^0.8 + 0.35K(r)/K(1), combining a modeled depth-loading term and cake resistance.')])
p('These are simplified engineering priors implemented in the application. Their physical motivation does not establish that their parameters describe the installed filters accurately. The fitted straight-line model approximates these nonlinear generated examples; it need not predict exactly 1.0 at nominal water conditions.')
heading('6. Wear accumulation and live health')
p('For consecutive credible Pump B counters, Δh = (counter₂ − counter₁)/3600. Negative counter changes re-anchor the counter. An increment larger than elapsed wall time plus five seconds is rejected as implausible. Repeated or older source events do not accrue additional wear.')
p('Runtime usage increases by Δu_run = Δh·L/H_rated. Calendar usage is u_cal = days_since_installation/D_rated. Effective usage is max(u_run,u_cal), and the health percentage is bounded 100·(1−usage).')
p('For source gaps longer than three minutes, the backend may recover runtime from cumulative counters, but accrues it at neutral L=1 and marks the service interval incomplete for training. It never creates missing water-quality readings. A runtime interval crossing a maintenance event is re-anchored for that stage rather than assigning pre-service runtime to new media.')
p('A rinse removes 70% of modeled runtime wear (usage becomes 0.30 times its previous value) and does not reset calendar age. A replacement starts a new stage installation and revision. The recovery fraction is a model assumption; rinsed intervals are excluded from end-of-life training targets.')
heading('7. Field observations and model adaptation')
p('A useful label describes an actual service outcome. The revised workflow records whether replacement was due to exhaustion, preventive maintenance, or damage. A training observation requires explicit exhaustion, positive measured operating hours, no rinses, and complete measured coverage. Preventive and damage replacements remain in history without being interpreted as the filter’s natural end of life.')
p('For an eligible completed interval: x̄ is the Pump B runtime-weighted average feature vector, and y = clamp(H_rated/H_observed,0.25,4). Eight eligible observations for a stage enable adaptation. Each real observation has weight 25, implemented by multiplying its row and target by √25=5. The 400 synthetic rows remain as the prior.')
p('The previous local implementation admitted replacement observations after modeled runtime usage reached 85% and retained at most 32 per stage. The revised backend archives observations and uses reported end-of-life condition and data completeness instead of treating the model’s own prediction as independent ground truth.')
heading('8. Local collection and monthly archive')
p('Realtime Database provides live telemetry, current shared filter state, and service-event synchronization. Each connected phone writes a Room journal of one received source reading per minute and observed operational events. Phones keep detailed records locally during the month. When no phone records a period, that period remains a visible gap.')
p('After the month closes in Asia/Manila time, WorkManager uploads immutable batches of up to 100 records to Firestore. Stable identifiers and content-addressed batches allow retry after interruption. The server normalizes source records and counts one deterministic source reading per minute across phones. Cumulative runtime differences are never summed separately for each phone.')
p('The backend runs every six hours and provides a 24-hour upload window after month-end before publication. Android and connectivity can delay phone uploads. Late uploads improve historical analytics but never replace a published forecast for that month. Newly available training observations can contribute to the next publication.')
heading('9. Monthly date calculation and publication')
p('For a closed month, the backend reads archived observations and records. It fits each stage model, calculates observed pump duty cycle, uses average archived water conditions to predict future load, and starts from the last archived cumulative stage state. Monthly publication does not reinterpret every past wear increment under new coefficients; those increments retain their historical model context.')
p('Remaining operating hours = max(0,1−u_run)·H_rated/L. Runtime-limited due time = last archived sample time + remaining_hours/(24·duty_cycle) days. Calendar-limited due time = installation time + D_rated days. The earlier due time becomes the service date.')
p('A date requires at least 60 recorded minutes, at least 10% coverage of the full month, a valid sample within 24 hours of month-end, and a measurable duty cycle of at least 0.001. These software guards are not validated statistical confidence thresholds. Insufficient data produces an explicit status without a fabricated date.')
p('Reports store the reporting month, source cutoff, publication time, model version, coefficient vectors, training observation IDs and digest, input record count/digest, coverage, and per-stage ISO date. The UI formats the date as Month Day, Year and never adds remaining days to the current date on each visit. A replacement supersedes the old installation’s forecast immediately; the new filter waits for its monthly report.')
heading('10. Illustrative calculation')
p('Consider activated carbon with a 350-hour reference, 100 accumulated equivalent hours, predicted future multiplier 1.20, and measured duty cycle 0.25. Remaining operating time is (350−100)/1.20 = 208.33 hours. Remaining elapsed time is 208.33/(24×0.25) = 34.72 days.')
p('If the latest archived sample is at the start of November 1, 2026, the runtime date is approximately December 5, 2026. If the filter was installed October 15, its 42-day calendar reference ends November 26. The displayed replacement date is therefore November 26, 2026. These are illustrative inputs, not experiment results.')
heading('11. Processing flowchart')
img = Image.new('RGB', (1250, 1500), '#F5FAFB'); draw = ImageDraw.Draw(img)
font_path = Path('C:/Windows/Fonts/calibri.ttf')
font = ImageFont.truetype(str(font_path), 32) if font_path.exists() else ImageFont.load_default()
steps = [
 'ESP32 readings → Realtime Database',
 'Server timestamps + shared filter state',
 'Connected phones → local Room history',
 'Month closes → retryable Firestore uploads',
 'Validate, deduplicate, mark recording gaps',
 'Read qualified end-of-life observations',
 'Fit weighted ridge models + analytics',
 'Enough data? Date : insufficient-data status',
 'Publish immutable report → synchronize phones',
 'Late records update history; next forecast uses them',
]
for i, text in enumerate(steps):
    y = 28 + i * 144
    draw.rounded_rectangle((45, y, 1205, y+101), radius=20, fill='white', outline='#0D7A8A', width=3)
    bounds = draw.textbbox((0,0), text, font=font)
    draw.text(((1250-(bounds[2]-bounds[0]))/2,y+32),text,font=font,fill='#0D2B4E')
    if i < len(steps)-1:
        draw.line((625,y+103,625,y+136),fill='#0D7A8A',width=4)
        draw.polygon([(616,y+127),(634,y+127),(625,y+140)],fill='#0D7A8A')
image_path = ROOT/'algorithm-flowchart.png'; img.save(image_path)
doc.add_picture(str(image_path), width=Inches(5.8))
heading('12. Pseudocode')
code = '''ON SOURCE EVENT:
  reject duplicate or older source event
  validate counters and source readings
  update shared stage wear; mark uncertain intervals
  phones store received minute samples and events locally

ON MAINTENANCE:
  compare expected stage revision with shared revision
  apply service once; record observed condition and reason
  retain qualifying exhaustion outcome for monthly upload

AFTER MONTH CLOSE:
  phones upload closed-month records with retry
  server validates and deduplicates uploaded records
  update monthly analytics and coverage
  after collection window, if no published report exists:
    read archived qualifying outcomes
    fit weighted ridge model separately for each stage
    derive service date if publication guards are satisfied
    create immutable report and mirror it to all phones
  late uploads improve history and future training only'''
run = doc.add_paragraph().add_run(code); run.font.name='Consolas'; run.font.size=Pt(9)
heading('13. Validation and limitations')
p('The existing R-squared value evaluates fit against generated baseline data. It is not field prediction accuracy, a confidence percentage, or evidence that the model predicts physical failure within a stated error. Field evaluation should reserve completed service intervals for evaluation and report date error or operating-hour error, sample size, and data coverage separately from training metrics.')
p('The revision includes automated cases for source ordering, duplicate records across phones, counter resets, gaps, maintenance revisions, qualified observations, month boundaries, insufficient data, and stable dates. Android compilation and tests run in GitHub Actions. Device and deployed Firebase behavior require staging/rollout checks; the document does not claim they have already passed.')
p('Other limits include configured ratings, no direct media-capacity sensor, shared source outages, phone recording gaps, reporting latency, uncertain user-reported exhaustion, changing flow conditions, and averages that may hide peaks. The backend and firmware paths currently represent one physical system. Cloud billing, security membership, and deployment must be configured before collection works.')
heading('14. Implementation references')
table(['File', 'Responsibility'], [
('data/ml/FilterLifeModel.kt','Original feature engineering, wear factor, and health calculations.'),
('data/ml/RidgeRegression.kt','Original ridge solver and fit diagnostic.'),
('data/ml/SyntheticWearData.kt','Original deterministic baseline generator.'),
('backend/functions/model.js','Versioned monthly model, deduplication, service labels and forecast.'),
('backend/functions/index.js','Telemetry triggers, maintenance transactions, ingestion and scheduled publication.'),
('data/history/*.kt','Room journal, shared state synchronization, closed-month upload.'),
('ui/history/*.kt; ui/filter/FilterFormatter.kt','History, charts, timeline, and fixed formatted dates.')])
heading('15. Platform references')
for text in [
 'Android Room persistence library: https://developer.android.com/training/data-storage/room',
 'Android WorkManager scheduling: https://developer.android.com/topic/libraries/architecture/workmanager/how-to/define-work',
 'Firebase Realtime Database events: https://firebase.google.com/docs/functions/database-events',
 'Firestore transactions and batches: https://firebase.google.com/docs/firestore/manage-data/transactions',
 'Firestore offline synchronization: https://firebase.google.com/docs/firestore/manage-data/enable-offline',
 'Cloud Functions setup and deployment: https://firebase.google.com/docs/functions/get-started',
]: p(text)
p('Algorithm statements and numerical constants above are sourced from this repository’s implementation. The platform references describe the storage and scheduling facilities; they do not validate the filtration model or its physical parameters.')
doc.core_properties.title='HydroSense Machine-Learning Algorithm for Filter Lifespan Estimation'
doc.core_properties.subject='Ridge regression, monthly archives, and fixed service dates'
doc.core_properties.author='HydroSense Project'
output=ROOT/'HydroSense_Machine_Learning_Algorithm.docx'
doc.save(output)
print(output)

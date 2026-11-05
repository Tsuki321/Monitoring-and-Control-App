# HydroSense flowcharts

The application and physical system have **two separate flowcharts**.

| Flowchart | Printable PDF | Image | Editable diagram |
|---|---|---|---|
| 01 — Android application | [App PDF](app-flowchart.pdf) | [PNG](app-flowchart.png) / [SVG](app-flowchart.svg) | [diagrams.net](app-flowchart.drawio) |
| 02 — Water filtration system | [System PDF](system-flowchart.pdf) | [PNG](system-flowchart.png) / [SVG](system-flowchart.svg) | [diagrams.net](system-flowchart.drawio) |

The PDFs use A3 pages for legibility. The SVGs can scale without losing sharpness.
Open either `.drawio` file in diagrams.net to edit individual shapes and labels.
The adjacent JSON files are the diagram sources. Run `python docs/flowcharts/render_flowcharts.py`
with Pillow and ReportLab installed to regenerate the exports; this does not build the Android app.

## 01 — Application scope

Based on the repository's Android source, including the requested removal of pump speed and
voltage displays and the change to measured runtime only for filter-life estimates.

- Startup, saved session, login/registration, consent, and the guided tutorial.
- Dashboard, monitoring, pump controls, filter health, settings and logout.
- Pump commands and actual relay states are separate Firebase values. Manual switches are
  disabled in AUTO mode and when a leak is reported; hardware safety remains the controller's job.
- Filter-life estimates require measured `/sensors/runtimeB` and usable water-quality readings.
  Missing data shows a waiting state; it does not generate runtime or advance wear from a timer.
- Model training on generated data is intentionally retained. Generated training examples and
  measured live inputs serve different purposes. Remaining filter life is still an estimate.
- The first measured counter establishes a baseline rather than charging the device's entire
  lifetime. Later counter differences capture measured operation while the app was closed.
- Old history with no measured counter is cleared because earlier releases could create it
  from accelerated simulated runtime. Existing measured history is retained.

Primary code: `SplashActivity.kt`, `MainActivity.kt`, `res/navigation/nav_graph.xml`,
`ui/auth/*`, `ui/control/*`, `ui/filter/*`, `ui/settings/*`,
`FirebaseRealtimeSensorRepository.kt` and `FilterHealthRepository.kt`.

## 02 — System scope

Based on the locally supplied thesis **V23 — Microcontroller-Based River Water Filtration
System for Barangay Longos Using a 5-Stage Filter and Biofilter with an IoT Android-Based
Monitoring System** (185 PDF pages).

| Thesis location | What the chart uses |
|---|---|
| pp. 60, 179 — existing system flowchart | Main control loop, AUTO/MANUAL branches and safety overrides |
| pp. 61–62 — schematic explanation | River intake, biofilter/catchment, filter-feed pump, five-stage treatment including UV, output tank |
| pp. 166–169 — Appendix I constants and initialization | Pump cycle times, telemetry and control intervals, startup and saved runtime |
| pp. 169–175 — main loop | Sensor sampling, runtime accumulation, float-switch rules and relay updates |
| pp. 176–177 — helper functions | Telemetry fields, actual pump status, full-tank command clearing |

Where the text and appendix disagree, the chart follows the executable conditions and
constants in Appendix I: telemetry every **5 seconds**, LCD cycling every **2 seconds**,
and the full-tank stop at **100% or higher**. The prose on p. 62 describes different telemetry
timing and a strictly-greater-than full condition; a code comment also says LCD updates every
4 seconds despite its 2-second constant.

The chart shows the continuous loop directly; it does not imply that each pass reboots the
controller. Leak and full-tank overrides apply to both control modes. An impossible float
combination (lower down, upper up) leaves both AUTO targets off, matching the initialized
targets in the supplied code.

The top row shows the physical water route within the same system figure. The five biofilter
media stages and the downstream five-stage treatment are distinct assemblies. The app's filter
estimate wording reflects the requested source changes, not a claim that the original thesis
already describes measured-input-only behavior.

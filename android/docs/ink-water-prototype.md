# Ink water — vehicle design prototype

> Handoff clarification (2026-11-13): the verification entries below are historical results from 2026-11-12. Local recordings and screenshots are excluded from the repository; clone/download includes source and reproducible tests, not those media files. See [the current handoff](partner-handoff-and-backend-roadmap.md) for release and backend status.

## Scope
Phone UI and the recommendation implementation are unchanged. The new vehicle surface has no dependency on location, backend, recommendation or player APIs. VehicleAudioScene supplies normalized regions, selection, title, playback and restriction state. The partner's algorithm can supply a separate adapter later.

The renderer is a procedural 2.5D material, not a physical fluid simulation or Blender animation. AGSL draws advected pigment, feathered diffusion, reflected droplets, impact ripples and a rebound jet on Android 13+. The earlier luminous contour rings have been removed. Canvas provides a portable fallback on older Android or shader creation failure. No engine dependency, native library, microphone permission or generated bitmap is added.

## Preview and launch
The debug APK adds an **Ink Studio** launcher activity. It is absent from release. The simulator uses synthetic regions, titles and a fictional navigation panel. Its playback buttons animate state only; they do not play audio or guide a vehicle.

- Split: navigation fixture beside narrow ink panel (when width >= 700dp).
- Wide: full available panel.
- Parked / Driving: simulated visual restriction; never changes actual car state.
- Still: freeze motion.
- Canvas: exercise the fallback.
- Play / Pause: settle or resume water.
- Next: transition selection and track title; start a fresh drop at the selected region.
- Drop: replay the drop / impact / rebound / diffusion sequence without changing the track.

Actual AAOS UX restrictions still apply. There is no distractionOptimized declaration or bypass. Launch only while the emulator is parked. This does not put custom graphics inside Android Auto's standard media templates or Hyundai's native navigation.

## Rendering lifecycle
The frame clock advances only when visible and motion is enabled. Pause eases the flow to zero; foreground lifecycle stops the clock; system animation disable is respected. Driving lowers both flow speed and impact/reflection intensity. Time is consumed in the draw phase rather than recomposing the dashboard each frame. Shader and brush are retained, and only six regions are rendered. Titles and controls remain stationary.

The surface uses 2D normalized coordinates rather than GPS so the rendering engine can be replaced if an OEM uses a different operating system. An OEM adapter must supply actual display bounds, safe areas, visibility, UX restrictions and validated region projection. A screen-width test alone must never enable vehicle mode in the real phone app.

## Motion revision
Reference pages were inspected in the browser:
- https://www.youtube.com/watch?v=JsXNTnr_MWc — irregular spreading ink silhouette.
- https://www.youtube.com/shorts/1LjiKPvd3PU — reflected falling droplets, water jet and outgoing ripples.

The new original procedural sequence is 13 simulation seconds: 1.45-second fall, impact, brief crown and rebound, spreading pigment, then dilution. It is a stylized real-time approximation, not high-speed camera footage or a physically solved liquid. No reference footage is downloaded or bundled.

## Verification
2026-11-12:
- Created and ran Audio_Ink_Automotive, Android Automotive OS 15 (API 35 extension 15), Google APIs x86_64, automotive_1080p_landscape profile. The emulator's actual captured display is 1080×600.
- Built debug APK and instrumentation sources successfully.
- Ran InkSurfaceInstrumentedTest on emulator-5556: 2 tests, 0 failures, 0 skipped. Actual AGSL compilation and all region uniforms validated; selected track, pause and simulated restriction survive state restoration.
- lintDebug passed; pre-existing warnings remain.
- Inspected actual GPU-rendered split and full-panel screens. Confirmed Next changes the title and Driving changes the scene semantics. This is a visual-state simulation, not injected vehicle speed or real navigation.
- Re-ran InkSurfaceInstrumentedTest and lintDebug after the droplet revision: 2 tests passed; build and lint succeeded. The shader test includes eventStart and intensity uniforms.
- Recorded 20 seconds of the revised Wide view locally at `android/artifacts/2026-11-12/ink-droplet-v2.mp4` (screenrecord completed, 3,444,225 bytes). This recording is not included in Git.
- Visually inspected local split diffusion capture `android/artifacts/2026-11-12/ink-droplet-v2.png` and Wide rebound jet / ripple capture `android/artifacts/2026-11-12/ink-droplet-impact.png` after pressing Drop. These captures are not included in Git.
- Earlier ink-water-aaos.mp4 and ink-aaos-*.png artifacts show the superseded contour-based design.
- No sustained frame-rate, thermal or physical vehicle benchmark is claimed.

AAOS emulator results do not certify Hyundai hardware or OEM deployment. The navigation drawing is part of the debug simulation, not Google Maps, Hyundai navigation or an Android Auto custom panel.

Launch again:
`android emulator start Audio_Ink_Automotive`
`android run --device=<serial> --activity=com.kimyubin33.audioapp.vehicle.InkSimulatorActivity --apks=app/build/outputs/apk/debug/app-debug.apk`

The launcher activity is exported only in the debug manifest and handles no extras, URI or external data. The production manifest and phone navigation are unchanged.

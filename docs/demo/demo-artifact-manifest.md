# Mahoraga Memory MVP — Demo Video Artifact Manifest

Records the identity, format, and review status of the demo recording. The MP4
is a local, non-Git binary; only this manifest, the transcript, and the captions
are tracked. Fields marked **PENDING RECORDING** are completed by the presenter
after capture and full-playback review (see the recording checklist below);
TASK-019 stays incomplete until they are filled and the video is reviewed.

## Prepared inputs (verified this session)

- **Application source revision (prep time):** `168de44` (`master` after the
  TASK-018 merge). This task changes only `docs/`, which does not affect the
  build, so the recording may be made against the revision current at capture
  time; the build fingerprint below is the stable tie.
- **Build fingerprint (packaged JAR SHA-256, embedded in `evidence.json`):**
  `b2fc787769e6320d56426ba31dc24cbb8a4baf03c689ff7238de7b8f6dd739e1`
- **Normalized demo evidence SHA-256 (must match on the recorded run):**
  `f81d12c3c33f7a616b1e80b8ca9062d584a8a827bfa5dac7673a56499513ddd6`
- **Script/rehearsal reference:** [`demo-script.md`](demo-script.md),
  [`demo-runbook.md`](demo-runbook.md), and three matching rehearsals in
  [`rehearsal-log.md`](rehearsal-log.md).
- **Prerequisites re-verified:** `./mvnw -q verify` PASS; `scripts/demo.sh
  preflight` PASS.

## Artifact identity

| Field | Value |
|---|---|
| Video filename | `mahoraga-memory-mvp-demo.mp4` |
| Approved artifact location | `artifacts/demo/mahoraga-memory-mvp-demo.mp4` (Git-ignored) |
| Video SHA-256 | **PENDING RECORDING** |
| Byte size | **PENDING RECORDING** |

## Media format

| Field | Target | Actual |
|---|---|---|
| Container | MP4 | **PENDING RECORDING** |
| Video codec | H.264 | **PENDING RECORDING** |
| Resolution | 1920×1080 | **PENDING RECORDING** |
| Frame rate | 30 fps | **PENDING RECORDING** |
| Audio codec | AAC, intelligible speech | **PENDING RECORDING** |
| Terminal font | 16–18 pt minimum | **PENDING RECORDING** |
| Duration | target ~7:35, hard max 7:59 | **PENDING RECORDING** |
| Captions | WebVTT synced to narration | [`demo-captions.vtt`](demo-captions.vtt) (draft; reconcile to take) |
| Disclosure | visible "Synthetic MVP — no customer data" | **PENDING RECORDING** |

## Provenance and review

| Field | Value |
|---|---|
| Recording date | **PENDING RECORDING** |
| Source revision at capture | **PENDING RECORDING** (confirm; docs-only changes keep the build fingerprint stable) |
| Build fingerprint at capture | should equal `b2fc7877…d739e1` |
| Normalized evidence SHA-256 at capture | should equal `f81d12c3…9513ddd6` |
| Full-playback reviewer | **PENDING RECORDING** |
| Full-playback result | **PENDING RECORDING** |
| Known non-semantic presentation differences | **PENDING RECORDING** |

## Recording checklist (presenter, after prep)

The tracked materials above are prepared and validated. The following steps
require screen and microphone capture and are yours to perform; capture must not
begin without explicit presenter approval.

1. Confirm prerequisites still pass: `./mvnw -q verify` and
   `scripts/demo.sh preflight`.
2. Configure a 1920×1080 capture region, 16–18 pt terminal, notifications off,
   unrelated windows closed, no credentials or private paths on screen.
3. Approve the exact local capture action (e.g. the built-in screen recorder);
   do not install tools, enable a camera, or use cloud transcription without
   separate approval.
4. Begin capture before the on-screen "Synthetic MVP — no customer data"
   disclosure.
5. Follow [`demo-script.md`](demo-script.md) exactly and run
   `scripts/demo.sh present` once. Stop rather than improvise on any mismatch.
6. Export locally to `artifacts/demo/mahoraga-memory-mvp-demo.mp4`.
7. Reconcile [`demo-transcript.md`](demo-transcript.md) and
   [`demo-captions.vtt`](demo-captions.vtt) against the actual narration and
   timing.
8. Compute identity and inspect media metadata:

   ```bash
   shasum -a 256 artifacts/demo/mahoraga-memory-mvp-demo.mp4
   wc -c artifacts/demo/mahoraga-memory-mvp-demo.mp4
   # if ffprobe is already installed:
   ffprobe -v error -show_entries \
     format=duration,format_name:stream=codec_name,width,height,r_frame_rate \
     -of json artifacts/demo/mahoraga-memory-mvp-demo.mp4
   ```

9. Confirm the recorded run's `target/demo/transcript.txt` hash equals
   `f81d12c3…9513ddd6`.
10. Watch the entire exported video with captions and complete the full-playback
    review in TASK-019.
11. Fill the **PENDING RECORDING** fields above and the TASK-019 completion
    record. Keep the MP4 out of Git.

Do not place an absolute home-directory path in this file; use the repository-
relative path above or an approved external artifact URL.

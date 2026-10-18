# On-device control feedback

The control bar stays near the bottom of the display and exposes **Take over** and **End task**. There is no separate pause icon. Taking over pauses remote actions and changes the same button to **Return to AI**. Returning control waits for any dispatched gesture to finish. The panel is draggable, avoids system bars and the keyboard, and reflows at large font sizes.

The dark translucent surface keeps text legible over third-party apps. The panel does not request Android's full-screen blur-behind flag, so underlying app content stays clear. The full-screen effects window never receives input. Screen captures retain the real visible control UI, so reading the screen does not flash the overlay off. Capture metadata identifies the potential panel region in original screen coordinates; the control windows remain excluded from the app accessibility tree. During an intersecting remote gesture, the local panel becomes touch-through until the actual platform callback or watchdog releases it.

## Cursor and action label

A requested action first supplies its actual target coordinate to a cancellable 180–300 ms pointer approach. No input is dispatched during this phase. After the platform accepts input, a 240 ms ring and light pointer compression confirm the click. Only a successful completion callback changes the label to completed; it remains for 900 ms and fades over 200 ms. Cancelled preparation never performs the requested action or displays success. Accessibility-node targets are refreshed and revalidated after the approach, so moving or replaced targets fail rather than receiving a stale click. Repeated clicks at the same coordinate need no approach delay, preserving native double-tap timing. A zero-length tap retains its real origin. Swipes follow the platform path and duration; the label describes the active operation, such as clicking, scrolling, or typing. The white label has no status dot and does not display an idle “waiting” message. It avoids the pointer and screen edges and disappears after the action. Pause, handover, cancellation, and detachment clear feedback.

## Edge motion

Each edge has its own deterministic smooth rhythm. Three different periods modulate opacity and glow depth, while a slower independent period moves a local highlight along the edge. The result varies across the four sides rather than applying one opacity animation to the whole frame. No per-frame random values are used, avoiding flicker. Idle rendering updates less often while retaining the established control appearance. An explicit mode entry grows the glow inward from all four edges over 500 ms; exit retracts its width and intensity before detaching. Reversing a transition continues from its current extent. Tool start/end does not trigger an entry or exit. System animation disablement freezes brightness, depth, and highlight position.

The motion model is platform-independent (`ControlEdgeMotion`); Android drawing is isolated from gesture feedback. Tests cover independent edges, continuity, bounds, long uptimes, and reduced motion.

## Persistent control mode

The dashboard exposes **Start AI control** and **Exit AI control** separately from the server start/stop controls. Start requires a running server. The first remote tool may also engage an initially ready session. Completing a tool changes the session to waiting, with its frame and local controls still present. An explicit local exit cancels current tool coroutines and blocks remote reentry until **Start AI control** is selected; a stale resume event cannot reopen an explicitly exited session. Server stop/reset closes the control mode.

Takeover and pause retract the frame while retaining the local bar so the user can return control. Ending the task waits for any dispatched platform gesture, then completes the visual exit before stopping the server. The mode lifecycle is independent of cursor completion and screenshot capture.

## Appearance settings

Open **Settings → Control experience → Edge lighting** to change the effect without restarting the server. The page uses the same native effects view for a scaled phone preview; it does not dispatch gestures or interact with another app.

The three color modes are **Multicolor glow** (`MULTICOLOR`, the default), **Classic blue** (`BLUE`), and **Custom color** (`CUSTOM`). Multicolor combines blue, violet, pink, and cyan with independent movement. Custom color supports named swatches and an optional six-digit RGB code; incomplete input stays in the editor and cannot replace a valid color.

| Setting | Range | Default |
| --- | --- | --- |
| Glow size | 16–96 dp | 56 dp |
| Color depth / intensity | 0.2–1.0 | 0.9 |
| Motion range | 0–1.8 | 1.2 |
| Motion speed | 0.5–2.0 | 1.0 |

`ControlEffectsConfig.normalized()` bounds all values and makes custom colors opaque. The existing settings DataStore stores the complete configuration in the single JSON preference key `control_effects_config`; this feature does not create a second DataStore. The live configuration Flow updates an attached overlay, so saved changes apply to an active session without a server restart.

Dragging a slider updates the local preview immediately, and releasing it saves the completed value. Mode and swatch selections save on selection. Writes are serialized, and a matching Flow acknowledgment completes an edit, preventing delayed initial values or older writes from replacing a newer gesture. Failed saves preserve the preview and expose a retry action. Restore defaults saves the multicolor preset and the values above.

When system animations are disabled, the selected palette remains visible while color cycling, edge geometry, and highlight positions freeze. In multicolor mode this preserves all four colors rather than reverting to a single blue border.

## Hardware acceptance

`ControlOverlayGestureDeviceTest` is opt-in (`overlayGestureDemo=true`) and uses only an owned native test page. It exercises the production action executor, checks that no click reaches the target during pointer approach, confirms exactly one real click and a real scroll, then verifies continuous overlay visibility during three accessibility screenshots. It also checks that explicit exit removes the panel while the actual server remains running, and that local reentry restores the overlay. `recordDemo=true` adds recording holds. Enable the app's existing accessibility service and system animations before running it.

An optional strict UUID argument, `ownedLaunchToken`, lets an external device harness launch the owned task with the same `control_overlay_acceptance_launch_token` intent extra. The test accepts only the matching `MainActivity` reaching `RESUMED` within 15 seconds and cleans up only its own task; startup failures are assertions, not skipped gesture acceptance. Native renderer and session tests independently cover cancellation, reduced motion, stale targets, and persistent idle state.

## ego-lite reference and source boundary

Reference source was reviewed at commit [`4cf79356509bccdead17863250b442f60a881c25`](https://github.com/citrolabs/ego-lite/tree/4cf79356509bccdead17863250b442f60a881c25):

- [`page-actions.ts`, lines 272–295](https://github.com/citrolabs/ego-lite/blob/4cf79356509bccdead17863250b442f60a881c25/package/ego-browser/src/driver/page-actions.ts#L272-L295) derives real coordinates and sets the action label before moving the display pointer.
- [`page-actions.ts`, lines 1824–1877](https://github.com/citrolabs/ego-lite/blob/4cf79356509bccdead17863250b442f60a881c25/package/ego-browser/src/driver/page-actions.ts#L1824-L1877) treats the pointer and label as display-only feedback, independent of action success.
- [`native-bindings-api.md`, lines 644–687](https://github.com/citrolabs/ego-lite/blob/4cf79356509bccdead17863250b442f60a881c25/docs/native-bindings-api.md#L644-L687) documents native display hooks, not their internal rendering.
- [`README.md`, lines 153–155](https://github.com/citrolabs/ego-lite/blob/4cf79356509bccdead17863250b442f60a881c25/README.md#L153-L155) distinguishes the MIT-licensed repository from the separately distributed browser.

The repository does not include the browser's native arrow artwork, tooltip geometry, or four-edge animation implementation. Android geometry, edge rhythms, and configurable multicolor rendering are independently implemented from the visual reference; they are not claimed to be a port of unpublished rendering source. No ego-lite implementation code is copied into this feature.

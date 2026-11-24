# Motion: signed-off values

Signed off by the owner on 2026-11-21 from the interactive prototype
`motion-prototype.html` (in this folder; open it in a browser), Expressive feel.
This file is the only input to `ui/agent/AgentMotion.kt` (task 6.1). Distances in dp,
W = width of the screen area under the title bar.

## Springs (Material 3 Expressive motion scheme, as plain values)

| Name | Stiffness | Damping ratio |
|---|---|---|
| spatial | 380 | 0.8 |
| fastSpatial | 800 | 0.6 |
| effects | 1600 | 1.0 |
| fastEffects | 3800 | 1.0 |
| screen | 380 | 1.0 |

`screen` is `spatial` with the damping raised to 1.0: a whole screen never overshoots
(an overshoot shows a gap at the edge). Things smaller than the screen (menu, tiles)
use the bouncy springs. The Expressive numbers match Material's published motion
tokens as ported in third-party libraries; confirm against androidx `MotionTokens`
if a material3 source is at hand, and change this file (not the code) if they differ.

## Per change

- `toChat` / `toTerminal` (fade-through, no slide): outgoing opacity 1 to 0 on
  fastEffects; then incoming opacity 0 to 1 on effects and scale 0.96 to 1 on screen,
  together. Sequential: the incoming starts when the outgoing is gone.
- `pushChanges`: Changes translateX W to 0; the base translateX 0 to -0.12 W and its
  dim 0 to full scrim (Graphite black at 42%, Paper rgb(20,20,30) at 16%). All on screen.
- `popChanges` (Back button): the exact reverse of `pushChanges`, on screen.
- Predictive Back on Changes, progress p from the system back event (0 to 1):
  Changes scale 1 to 0.90, shift 24 dp x p toward the swipe edge's direction,
  vertical follow 0.12 x finger dy clamped to +-28 dp, times p; corner radius 28 dp x p;
  shadow grows with p. Base translateX -0.12 W x (1 - 0.45 p), dim x (1 - 0.55 p);
  the base's shift takes the side of the swipe edge (from the right edge it comes in
  from the right, +0.12 W x (1 - 0.45 p), and the card shifts left). The system decides:
  when it commits Back, the motion finishes from where it is, at the finger's velocity:
  Changes slides off to +-W in the swipe direction at its current scale, base to 0 (same
  sign), dim to 0, on screen. When it cancels, p returns to 0 on screen. (The unseeked
  pop, Back button or key, keeps -0.12 W, as the push mirrors it.)
  (The prototype measured p as distance / 0.55 W; the app uses the system's progress.)
- `pushOverview`: the current screen shrinks into its tile (translate + uniform scale to
  the tile's rect) on spatial; visual corner radius 14 dp x t; the overview background
  fades in with t; the current tile's label fades in with t. The other tiles follow after
  70 ms + 45 ms per tile, on fastSpatial: opacity min(1, 1.4 x), scale 0.88 to 1.
  Grid: 2 columns, 16 dp padding, 12 dp gap, 26 dp label row, tile aspect = screen aspect.
- `popOverview` (a tile tapped, or Back to the current tab): that tile grows to full
  screen, the reverse of the shrink, on spatial; the other tiles fade out on fastEffects
  with scale 1 to 0.94.
- `menu`: opens on fastSpatial from the button (origin at the button's centre, top edge):
  scale 0.82 to 1, opacity min(1, 2 x); item i follows progress clamp(1.6 x - 0.12 i),
  translateY -4 dp to 0. Closes on fastEffects: opacity 1 to 0, scale 1 to 0.96.
  A tap outside only closes it.
- Title bar content: crossfade, 110 ms out, then the new content.
- `leave` (Back from the agent's base to the agent list; the owner's choice,
  "like Changes"): the agent screen moves as Changes does in its predictive
  Back and its release (same springs, scale, shift, corner radius, system-decided release), with the
  agent list in the base's place (translateX and dim as the base under Changes, mirrored by the swipe edge). A Back
  button or key runs the same motion unseeked. The list's own content is not animated.

## Always

- System "remove animations" (animator duration scale 0): every change is an instant cut.
- While a change runs, no touch reaches any screen.

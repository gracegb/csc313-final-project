# Final Project - Developer README

## About the Proj

A 1v1 local multiplayer 3D fighting game written in Java using LWJGL (GLFW + OpenGL).

- Runtime: desktop Java app
- Rendering: OpenGL immediate mode (`GL11` calls)
- Input: keyboard polling via GLFW
- Assets: OBJ/MTL models + PNG textures

## Repository Layout

```text
final-project/
  src/main/java/
    FightingGameLWJGL.java      # Main and currently all gameplay/render logic
  models/
    */model.obj                 # Fighter meshes
    */model.mtl                 # Material definitions
    */texture_*.png             # Diffuse textures
  build.gradle.kts              # Gradle + LWJGL dependencies
  settings.gradle.kts
  gradlew / gradlew.bat
  gradle/wrapper/*

  SimpleObjRenderer.java        # Legacy Swing renderer prototype (not active runtime)
```

## How To Run

From `final-project/`:

```bash
./gradlew classes
./gradlew run
```

## Tech Stack

- Java 17 toolchain
- LWJGL 3.3.3
  - `lwjgl`
  - `lwjgl-glfw`
  - `lwjgl-opengl`
  - `lwjgl-stb` (used for text rendering)
- Gradle wrapper included

## Architecture Overview

The project is currently a **single-file architecture** in:

- `src/main/java/FightingGameLWJGL.java`

To reason about "frontend" vs "backend", use this split:

### Frontend (Rendering + Input + UX)

Responsibilities:
- Window/context setup (`initWindow`)
- Frame loop (`loop`)
- Keyboard input polling (`isDown`, `isPressed`, `snapshotKeys`)
- 3D scene drawing (`render`, `drawArena`, fighter draw methods)
- 2D UI overlays (`beginOverlay`, `drawRectPx`, `drawText`)

Key methods:
- `render()`
- `drawCharSelectOverlay()`
- `drawFightOverlay()`
- `drawText()` (STB Easy Font)

### Backend (Game State + Rules + Asset Pipeline)

Responsibilities:
- Game-state transitions (`CHAR_SELECT` -> `FIGHT`)
- Round setup and winner logic
- Movement and combat rules
- Attack timers/cooldowns/ranges/damage
- OBJ/MTL parsing and texture loading

Key methods:
- `update()`
- `updateCharacterSelect()`
- `startRound()`
- `updateFight()`
- `updateAttackState()`
- `resolveHit()`
- `loadModels()` and `ObjLoader.*`

Data structures:
- `Fighter` (player runtime state)
- `FighterModel` (name + mesh + tint)
- `Mesh`, `MeshPart` (render-ready geometry buckets)
- `GameState`, `AttackType`

## Gameplay Features Currently Implemented

- Character select for both players
- Ready system for both players
- Auto transition into fight when both ready
- Two-player movement and attacks
- Punch/kick with distinct cooldowns, active frames, and ranges
- Health bars + winner handling
- Return-to-select flow after match

Controls:
- P1: `WASD` move, `F` punch, `R` kick
- P2: `Arrow Keys` move, `.` punch, `SHIFT` kick

## Asset/Model Pipeline

Models are discovered by walking `models/` recursively for `.obj` files.

OBJ support:
- `v`, `vt`, `f`, `usemtl`, `mtllib`

MTL support:
- `newmtl`, `map_Kd`

Texture behavior:
- `map_Kd` is resolved relative to OBJ directory
- textures are loaded with `ImageIO` and uploaded to OpenGL
- geometry is grouped by texture id, then rendered by mesh part

## Important Constants (Balancing + Feel)

Near the top of `FightingGameLWJGL`:
- arena size and movement speed
- health and damage values
- attack active frame counts
- attack cooldown values
- hit ranges

Adjusting these constants is the fastest way to tune gameplay feel.

## Developer Workflows

### 1) Tuning combat
Edit constants + `resolveHit` logic.

### 2) Changing controls
Edit `TRACKED_KEYS` and key usage in:
- `updateCharacterSelect`
- `updateFight`

### 3) Updating UI/HUD text
Edit:
- `drawCharSelectOverlay`
- `drawFightOverlay`
- `drawText`

### 4) Adding or swapping fighter assets
Add a new subfolder under `models/` with at least:
- `model.obj`
- optional `model.mtl` and texture files referenced by `map_Kd`

### 5) Rendering improvements
Current renderer is immediate mode; for performance and scalability, plan migration to VBO/VAO/shader pipeline.

## Known Limitations / Technical Debt

- Single large source file (harder to maintain)
- Immediate-mode OpenGL (not modern pipeline)
- No animation rig; attack visual is positional push/lean only
- Basic collision model (range + facing checks)
- No networking/server backend (local multiplayer only)

## Suggested Refactor Plan

Short-term (safe):
1. Split `FightingGameLWJGL` into packages/classes:
   - `game/` (state and combat)
   - `render/` (OpenGL renderer + UI)
   - `assets/` (OBJ/MTL/texture loading)
   - `input/` (input mapping)
2. Keep behavior identical while moving code.

Medium-term:
1. Introduce a small game state container and deterministic update tick.
2. Move drawing to mesh buffers and shaders.
3. Add lightweight scene graph / entity model.

## Troubleshooting

### Text not visible
- Ensure `lwjgl-stb` dependency is present in `build.gradle.kts` (it is currently).
- Ensure overlay path calls `beginOverlay()`/`endOverlay()` and text color contrasts with background.

### Textures not visible
- Check OBJ references a valid MTL via `mtllib`.
- Check MTL has correct `map_Kd` relative path.
- Confirm texture file exists and is readable.

### Build issues on macOS
- `-XstartOnFirstThread` is already set in `applicationDefaultJvmArgs` for macOS.

## Legacy Note

`SimpleObjRenderer.java` is the earlier Swing path and is not the active runtime. Kept for reference.


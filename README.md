# 👁 Eye Tracker — Android App

Tracks your gaze using the front camera and draws a soft spotlight highlight wherever you're looking on screen — even while using other apps.

---

## How It Works

```
Front Camera → ML Kit Face Detection → Head Pose (yaw/pitch) → Screen Coordinate → Overlay Highlight
```

- **ML Kit Face Detection** detects your face at ~30fps via CameraX
- **Head Euler angles** (yaw = left/right, pitch = up/down) are mapped to screen X/Y
- An **exponential moving average** smooths the position to reduce jitter
- A **WindowManager overlay** draws the gaze circle on top of all apps

> ⚠️ This uses *head pose estimation*, not true iris gaze tracking. For pixel-accurate gaze you'd need specialized hardware (e.g. Tobii). Head pose is a good approximation for screen-level interaction.

---

## Setup in Android Studio

### 1. Open the project
```
File → Open → select the EyeTracker/ folder
```

### 2. Sync Gradle
Click "Sync Now" when prompted.

### 3. Permissions needed (auto-requested at runtime)
| Permission | Why |
|---|---|
| `CAMERA` | Front camera for face detection |
| `SYSTEM_ALERT_WINDOW` | Draw overlay on other apps |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` | Background service |
| `POST_NOTIFICATIONS` | Persistent notification (Android 13+) |

### 4. Build & Run
- Connect an Android device (API 26+) or emulator with front camera
- Run → Run 'app'

---

## App Features

| Feature | Description |
|---|---|
| Start/Stop | Toggle tracking; runs as foreground service |
| **Eye Calibration** | 9-point calibration system for improved accuracy |
| Sensitivity | How much head angle maps to screen range |
| Highlight Size | Radius of the gaze circle (30–120dp) |
| Color | Red / Blue / Green / Yellow spotlight |
| Fade in/out | Highlight fades when no face is detected |
| Foldable Support | Auto-detects fold/unfold events and prompts recalibration |

### Calibration System

The app now includes a comprehensive calibration system to significantly improve eye tracking accuracy:

- **9-Point Grid Calibration**: Look at 9 target points in a 3x3 grid
- **Live Camera Preview**: See your eyes during calibration
- **Visual Indicators**: Pulsing circles show where to look
- **Progress Tracking**: Shows "Point X of 9" during calibration
- **Quality Score**: Displays calibration quality (0-100%) after completion
- **Automatic Prompt**: Asks if you want to calibrate on app start
- **Foldable Device Detection**: Prompts recalibration when device is folded/unfolded
- **No Persistent Storage**: Calibration resets when app closes (by design)

The calibration uses affine transformation to map raw head pose angles to accurate screen coordinates, compensating for individual user differences in posture, viewing distance, and head movement patterns.

---

## Project Structure

```
app/src/main/
├── java/com/eyetracker/
│   ├── MainActivity.kt              # Main UI, permissions, calibration prompts
│   ├── EyeTrackingService.kt        # Foreground service, CameraX, ML Kit
│   ├── GazeOverlayView.kt           # Full-screen transparent overlay
│   ├── CalibrationActivity.kt       # 9-point calibration screen
│   ├── CalibrationTargetView.kt     # Draws calibration targets
│   ├── CalibrationData.kt           # Calibration math & transformation
│   ├── CalibrationManager.kt        # Singleton calibration state manager
│   └── FoldableDeviceHelper.kt      # Detects fold/unfold events
├── res/
│   ├── layout/
│   │   ├── activity_main.xml        # Main screen layout
│   │   └── activity_calibration.xml # Calibration screen layout
│   ├── drawable/camera_preview_border.xml
│   └── values/themes.xml
└── AndroidManifest.xml
```

---

## Extending the App

### Further gaze accuracy improvements
- Use **ML Kit Face Mesh** (experimental) for iris landmark detection
- Apply **Kalman filtering** instead of EMA for better smoothing
- Add **persistent calibration storage** using SharedPreferences or Room

### Add heatmap recording
- Store gaze positions + timestamps in a Room database
- Export as CSV or render as a heatmap overlay image

### Dwell-based click
- Detect when gaze dwells in one area > 800ms → simulate a tap

### Multi-user profiles
- Store separate calibrations for different users
- Auto-detect user based on facial recognition

---

## Requirements

- Android 8.0+ (API 26)
- Device with front-facing camera
- ~50MB RAM for ML Kit model (auto-downloaded)

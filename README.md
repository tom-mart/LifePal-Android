# LifePal

An Android application that integrates with Health Connect to track health metrics and provides an AI-powered chat interface for health and wellness assistance.

## Features

### Health Data Collection
- Automatic sync with Health Connect every 15 minutes
- Collects steps, heart rate, exercise sessions (with GPS routes), and sleep data
- Writes weight and exercise data back to Health Connect
- Offline-first architecture with local database queuing

### Contextual Awareness
- Monitors app usage, device state, and environmental sensors
- Collects data every 15 minutes in the background
- All data persists locally until successfully sent to backend

### AI Chat Interface
- Multi-agent conversation system
- Real-time streaming responses
- Message history persistence
- JWT authentication

### Push Notifications
- Firebase Cloud Messaging integration
- Automatic device registration

## Architecture

Data is collected via WorkManager on 15-minute intervals, saved to a local Room database, then sent to the backend API when network is available. If offline, data queues locally with original timestamps and sends automatically when connectivity returns.

## Tech Stack

- **UI:** Jetpack Compose (Material3)
- **Language:** Kotlin
- **Networking:** Ktor Client
- **Database:** Room
- **Background Work:** WorkManager
- **Health Data:** Health Connect SDK
- **Messaging:** Firebase Cloud Messaging
- **Authentication:** JWT tokens
- **Serialization:** kotlinx.serialization

## Requirements

- Android API 35+
- Health Connect app installed
- Network connectivity for API communication

## Setup

1. Clone the repository
2. Open in Android Studio
3. Create a `local.properties` file in the root directory:
```properties
MY_KEYSTORE_PASSWORD=<your_keystore_password>
MY_KEY_ALIAS_PASSWORD=<your_key_alias_password>
```
4. Add your `google-services.json` file to `app/`
5. Build and run

## Permissions

The app requires various permissions including:
- Health Connect data access (steps, heart rate, sleep, exercise)
- Location (for GPS tracking during exercise)
- Usage stats (for app usage monitoring)
- Notifications
- Background data sync

All permissions are requested at runtime with appropriate explanations.

## Data Privacy

All health and contextual data is stored locally first and only transmitted to your configured backend server. The app does not share data with third parties.

# LifePal

LifePal is an Android application that helps users track their health and wellness. It connects with Health Connect to gather data and provides insights to the user.

## Features

* Fetches health data from Health Connect
* Runs a foreground service to continuously monitor data
* Uses Room database for data persistence
* Implements background data synchronization using WorkManager
* Sends push notifications using Firebase Cloud Messaging

## Tech Stack

*   **UI:** Jetpack Compose
*   **Asynchronous Programming:** Kotlin Coroutines
*   **Networking:** Ktor
*   **Data Storage:** Room, DataStore Preferences
*   **Analytics:** Firebase Analytics
*   **Push Notifications:** Firebase Cloud Messaging
*   **Health Data:** Health Connect
*   **Location:** Google Play Services for Location
*   **Background Processing:** WorkManager

## Setup

1. Clone the repository
2. Open in Android Studio
3. Create a `local.properties` file in the root directory with the following content:
```
MY_KEYSTORE_PASSWORD=<your_keystore_password>
MY_KEY_ALIAS_PASSWORD=<your_key_alias_password>
```
4. Build and run the application.

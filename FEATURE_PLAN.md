# AI Assistant Feature & Implementation Plan

This document outlines the feature list and technical plan for expanding the AI Assistant application with wearable support and advanced functionalities.

---

## I. High-Level Vision

The goal is to create a comprehensive AI assistant ecosystem consisting of a **Phone App**, a **Watch App**, and a **Backend**. The core interaction model is conversational, with the AI proactively engaging the user through notifications and data collected from the user's devices to provide personalized assistance.

---

## II. Phone App Feature Breakdown

The phone app serves as the primary user interface for chat, configuration, and data management.

### 1. Core Chat Interface (Mostly Implemented)
- **Description:** The main interface for users to communicate with AI agents.
- **What We Have:** A functional chat screen, agent selection, and conversation flow.
- **What We Need:**
    - **Message History API:** A backend endpoint to fetch historical conversations with a specific agent.
    - **UI for Message History:** The `LazyColumn` will need to support loading older messages as the user scrolls up (pagination).

### 2. Proactive & Actionable Notifications (To Be Implemented)
- **Description:** A key feature where the AI can initiate conversations or request data via notifications.
- **Technical Requirements:**
    - **Backend:** A new "push trigger" mechanism (`/notifications/trigger`).
    - **Android App:** `MyFirebaseMessagingService` logic to handle incoming "data" messages and build notifications using `NotificationCompat.Builder` and `RemoteInput`.

### 3. File Management (To Be Implemented)
- **Description:** A dedicated section for users to manage files.
- **Technical Requirements:**
    - **Backend:** Endpoints for file upload, listing, and deleting (`/files/...`).
    - **Android App:** A new file management screen and enhancements to the chat UI to display file-based messages.

### 4. Health & Contextual Data Collection (Implemented)
- **Description:** The phone app acts as the primary data hub, collecting a wide range of health, fitness, GPS, and contextual data.
- **What We Have:**
    - **Health Connect Integration:** The app can request permissions and use a `WorkManager` to periodically read health data (steps, heart rate, exercise sessions with GPS) that has been synced from a watch or other apps.
    - **Contextual Data Collection:** The app can request permissions and use a `WorkManager` to periodically collect app usage stats, device state (charging, DND), physical activity, and environmental sensor data.
- **What We Need:**
    - **Backend API Endpoints:** The backend needs to have live `/api/data/health` and `/api/data/contextual` endpoints to receive the data being sent.
    - **Data Transformation Logic:** The placeholder logic in the `HealthDataWorker` and `ContextualDataWorker` needs to be completed to format the collected data into the final JSON schemas and send it via the `ApiClient`.

---

## III. Watch App Feature Breakdown (New Module - To Be Implemented)

The watch app is a satellite application focused on data collection during specific, phone-free activities and providing timely notifications.

### 1. Application Module (To Be Implemented)
- **Description:** A new Wear OS module (`:wear`) must be created within the existing Android Studio project.

### 2. Health & Fitness Data Sync Strategy (To Be Implemented)
- **Description:** The watch's main role is to collect data *only when the user is away from their phone* (e.g., during a run).
- **Technical Requirements:**
    - The watch app will need logic to detect a phone-free state and activate its own data collection services.
    - It must use the **Wearable Data Layer API** to send the batched data to the phone once a connection is re-established.
    - **Local Storage (Room database)** is essential on the watch to store data collected while offline.

### 3. GPS Tracking (To Be Implemented)
- **Description:** The user can start and stop a GPS tracking session from the watch.
- **Technical Requirements:**
    - This requires a **Foreground Service** on the watch to keep the GPS active.
    - The service will save GPS points to the local Room database.

### 4. Notifications & Quick Replies (Partially Implemented)
- **Description:** Notifications will be mirrored from the phone, and quick replies are a key feature.
- **What We Have:** The phone app builds basic notifications.
- **What We Need:** The phone app's notification builder must be enhanced with `RemoteInput` to enable the reply functionality on the watch.

---

## IV. Backend API - Gap Analysis

#### What We Have:
- Authentication, Chat, Agent Listing, and Device Registration endpoints.

#### What We Need to Build:
- `GET /api/chat/history/{agent_id}`
- `POST /api/notifications/trigger`
- **`POST /api/data/health` (Crucial Next Step)**
- **`POST /api/data/contextual` (Crucial Next Step)**
- `POST /api/data/gps/session` (Note: This is now part of the Health Connect `exercise` data)
- `/api/files/...` endpoints.

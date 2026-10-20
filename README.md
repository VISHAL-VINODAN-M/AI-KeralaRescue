# Kerala Rescue — Android Java SOS App

This is the mobile citizen app for the Kerala Rescue project.

## Features
- One-tap Critical SOS
- GPS location using Android location services
- Internet -> POST critical SOS to Spring Boot backend
- No internet but cellular SMS service -> SMS fallback
- No usable network -> saves SOS locally
- No citizen login
- Configurable SMS destination, your own phone number (sent with the SOS so rescuers can call back) and backend URL
- SOS never waits more than ~8 seconds for GPS (falls back to last known location); an SMS-sent SOS is also queued for the server
- Works as the mobile companion to the existing Spring Boot responder dashboard
- Quick emergency type, priority, and condition selection for detailed requests
- Camera/gallery photo attachments, people/injured counts, request cancellation, messages, history, and active-request duplicate protection
- Foreground status polling with local Android notifications when the rescue team updates a request

## Open in Android Studio
1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Connect an Android phone or start an emulator.
4. Grant Location and SMS permissions.
5. Set the emergency SMS number.
6. Set backend URL. For an Android emulator, the PC's localhost is usually `http://10.0.2.2:8080/api/emergencies`.

Use Android Studio with a current Android SDK (API 35) and JDK 17 or newer. Do not copy `local.properties` from another computer; Android Studio creates it for that computer's Android SDK path.

## Important testing notes
- GPS can work without internet, but map links need the recipient to have connectivity.
- SMS fallback needs cellular service and a SIM/carrier that supports SMS.
- A completely disconnected phone cannot transmit an SOS to a remote server. The app stores the event locally until connectivity returns.
- For a real deployment, use an authorized emergency-services integration rather than claiming that an SMS automatically dispatches public emergency responders.

## Start the backend and view requests

The Java Spring Boot backend is in `backend/`. It uses Spring JDBC with a file-based H2 database. Open PowerShell in the extracted project folder and start the backend with:

```powershell
cd backend
..\gradlew.bat bootRun
```

Keep that terminal open. Open `http://localhost:8080/` in a browser for the responder dashboard. Incoming SOS and rescue requests appear there in priority order. Responders can set priority, status, helper name, and ETA; enter that helper name in **Helper assignments** to load the assigned requests and update progress. The citizen app polls for status while open and can send/read messages for its active request.

The H2 web console has been removed. To inspect the data, stop the backend and open `backend/data/kerala-rescue.mv.db` with the H2 shell tools.

The database file is saved under `backend/data/kerala-rescue.mv.db` and stays across backend restarts. It is created automatically on first run. Marking a request **RESOLVED** or **CANCELLED** removes it from the active request table and archives its details in `EMERGENCY_HISTORY`. Other requests are archived after one hour; cleanup runs once per minute and on backend startup. Messages remain in `EMERGENCY_MESSAGES`. Dashboard data is available at `GET http://localhost:8080/api/emergencies`, counts at `/api/summary`, analytics at `/api/analytics`, and archived records at `/api/history`.

## How a request is handled (who does what)

1. **Citizen** taps SOS (or sends a help request) in the Android app. It appears on the dashboard.
2. **Dispatcher** (you or a coordinator) opens the dashboard `http://<computer-ip>:8080/` and signs in with the responder key. Under **Helpers** they add each rescuer (name, phone). The server creates a Helper ID and a 6-digit PIN, shown once.
3. The dispatcher opens a request, picks a helper, sets an ETA and clicks **Assign**. The citizen's app immediately shows the team and ETA.
4. The dispatcher clicks **Message on WhatsApp** or **Send SMS** next to the assignment. That opens the dispatcher's WhatsApp/SMS with a ready message (request details, map link, and the helper's page link). Nothing is sent automatically; the dispatcher presses send. Automatic push to helpers would need a paid SMS/WhatsApp service or a helper mobile app.
5. **Helper** opens `http://<computer-ip>:8080/helper.html` on their phone, signs in with ID + PIN, and sees only their own assignments. The page beeps and vibrates when a new one arrives (while it is open). Buttons: Accept, I have arrived, Rescue started, Rescue complete, or "I cannot take this" (returns it to the dispatcher). Helpers can also message the citizen.
6. Every step the helper taps is shown on the citizen's phone and the dispatcher's dashboard.

Helper PINs are a convenience login, with a 5-wrong-tries lockout of 5 minutes. They are not a substitute for HTTPS on an open network.

## Access control (responder key + tracking tokens)

Set a shared responder key before starting the backend (at least 12 characters). If you do not, a random one is generated and printed in the log on each start:

```powershell
$env:RESCUE_RESPONDER_KEY = "pick-a-long-random-key"
..\gradlew.bat bootRun
```

Open the dashboard using the computer's Wi-Fi address (not `localhost`, so helper links work) and enter the key in the **Responder key** box. Rules enforced by the API:

- **Anyone** can create a request (`POST /api/emergencies`) and call `/api/health`.
- **A citizen** needs the request's private tracking token (sent by the app as `X-Tracking-Token`) to read it, read or send messages, or cancel it. A citizen can only set the status to CANCELLED, and messages are always recorded as USER.
- **Helpers** (headers `X-Helper-Id` + `X-Helper-Pin`) can list and update only requests assigned to them (`/api/helper/...`).
- **Dispatchers** (header `X-Responder-Key`) are needed for the request list, assignments, photos, summary, analytics, full history, status/priority/assignment changes and RESPONDER/HELPER messages.
- Re-sending an existing request ID with a different token returns 409, so nobody can read another person's record by guessing its ID.

The dashboard exchanges the responder key for a 12-hour login token (`POST /api/auth/responder`), and helpers do the same with ID + PIN (`/api/auth/helper`). The key is still a single shared secret, not per-responder accounts. Auth, translation, AI, live-stream and new-request endpoints are rate limited per client address (HTTP 429). Serve the backend over HTTPS (for example behind a reverse proxy) before reaching it from outside a trusted network. Video attachments are not implemented.

## Smart features (all optional)

Every feature below switches itself on only when its setting exists, and the original SOS flow never depends on any of them. The dashboard header shows which are on.

| Feature | What it does | Switch on with |
|---|---|---|
| **AI triage** | Each new request is analysed in the background (text and photo): suggested priority and type, one-line summary, warning flags, duplicate detection. It can only *raise* a priority, never lower it, and dispatchers always decide. Without a key a built-in rule-based triage runs instead. | `ANTHROPIC_API_KEY` (model via `RESCUE_AI_MODEL`, default `claude-haiku-5-5`) |
| **Translation** | Malayalam <-> English buttons on each request (`POST /api/translate`, dispatchers and signed-in helpers only). | `ANTHROPIC_API_KEY` |
| **Situation brief** | One-click summary of all open requests (`GET /api/ai/situation`). | works with or without a key |
| **Safety chat** | Public first-aid / safety guidance endpoint (`POST /api/ai/safety-chat`), rate limited. Not yet wired into the Android app. | works with or without a key |
| **Live updates** | Dashboard and helper page update instantly through server-sent events (`/api/stream`, `/api/helper/stream`); polling remains as a fallback. | always on |
| **Map** | Leaflet + OpenStreetMap tab showing requests and helpers who share their location. Needs internet in the browser. | always on |
| **Nearest helper** | "Suggest nearest" ranks helpers by distance and current workload (`GET /api/emergencies/{id}/nearest-helpers`). Helpers turn on *Share my location* on their page. | always on |
| **Push notifications** | Firebase Cloud Messaging to citizens (status changes, messages) and helpers (new assignments). | server: `FIREBASE_SERVICE_ACCOUNT_JSON`; app: put `google-services.json` in `app/` and rebuild |
| **SMS alerts** | Twilio SMS to helpers on assignment. | `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER`, `RESCUE_PUBLIC_URL` |
| **PostgreSQL** | Production database instead of the local H2 file. | On Render (the Dockerfile sets an H2 `SPRING_DATASOURCE_URL`, which would win over the profile): `SPRING_PROFILES_ACTIVE=postgres` plus your own `SPRING_DATASOURCE_URL` (`jdbc:postgresql://...`), `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`. Outside Docker you can use `RESCUE_DB_URL`, `RESCUE_DB_USER`, `RESCUE_DB_PASSWORD` instead. |

Set `RESCUE_JWT_SECRET` (16+ characters) so login sessions survive a server restart. Photos and descriptions are sent to the Claude API for triage, so only enable it if that is acceptable for your data.


For the Android emulator, leave the app backend URL as `http://10.0.2.2:8080/api/emergencies`; `10.0.2.2` routes from the emulator to the development computer. On a physical phone, connect the phone and computer to the same Wi-Fi, run `ipconfig` on the computer, and set the app URL to `http://<that-computer-wi-fi-ip>:8080/api/emergencies`. Open the dashboard on that computer at `http://localhost:8080/` (or from another device at `http://<that-computer-wi-fi-ip>:8080/`). The debug APK permits local HTTP for development; the release config stays restricted to localhost and the emulator. The release build only allows cleartext to localhost and the emulator, so a real server must use HTTPS.

## Request storage

The API stores the SOS ID, type, priority, status, location, description, people count, assigned team, ETA, and an optional photo. Photo bytes are saved in H2. Request IDs are idempotent, so a queued retry does not create a duplicate. The app sends optional `peopleNeedingHelp`, `imageBase64`, and `imageContentType` fields; images are limited to 2 MB by the backend.

## Deploy the backend on Render

The repository includes a Dockerfile and `render.yaml` Blueprint for Render. The backend uses a 1 GB persistent disk for the H2 database. Render's persistent disks require a paid web service; the current smallest web plan is $7/month and disk storage is $0.25/GB/month, so the configured service starts at about $7.25/month before bandwidth or tax. Review Render's current pricing before creating the Blueprint.

1. Push the project folder to a GitHub repository. The included `.gitignore` excludes `local.properties`, the local database, and build outputs. The debug APK under backend static resources is deliberately included so Render can serve it as a download.
2. In Render, choose **New → Blueprint**, connect that repository, and deploy `render.yaml`. The Blueprint generates the responder key and database password. Save the responder key from the service's environment settings; you will enter it on the dashboard.
3. When the service is live, open `https://<your-service>.onrender.com/api/health`. Then open `https://<your-service>.onrender.com/` for the responder dashboard.
4. Open the downloaded app and enter your live HTTPS API URL in **Settings → Backend URL** (replace the example with your Render service URL):

   ```text
   https://<your-service>.onrender.com/api/emergencies
   ```

   To bake the URL into a newly built APK instead, build it with:

   ```powershell
   .\gradlew.bat assembleDebug "-PrescueApiBaseUrl=https://<your-service>.onrender.com/api/emergencies"
   ```

5. Share `https://<your-service>.onrender.com/kerala-rescue.apk` with testers. They can install it from the download, allowing Android's unknown-app install prompt if shown. This debug APK is for testing; publishing on Google Play requires a separately signed release build and Play Console setup.

The Android app's default URL remains the emulator development URL. The build property above sets the deployed URL in the APK without changing the one-tap SOS flow. You can also enter the deployed URL in the app's **Settings → Backend URL** field. The dashboard uses the generated responder key; citizen requests do not require that key.

This project is a prototype and does not connect to an official emergency-services dispatch network. Do not rely on a self-hosted demo backend as the sole way to request emergency help.

MASHWARAK Android V1.13.0 - Native Google Location Picker

Purpose:
- Test a cleaner Uber/Careem-style location flow inside the APK without changing the backend architecture.
- FROM: current location is the primary flow.
- TO: native Google map with search + movable map + confirm.
- Existing manual governorate/center/village lists remain as fallback.
- Exact lat/lng + readable address continue to be saved in the existing V5.3.0 backend fields.

Important Google Maps setup:
- The project now includes Maps SDK for Android.
- It uses MASHWARAK_MAPS_API_KEY GitHub secret when present.
- For testing, Gradle falls back to the existing project API key in mashwarak-config.properties.
- If the native Google map appears blank, enable "Maps SDK for Android" in the Google Cloud project and use a key authorized for Android package com.jekonix.mashwarak.
- For production, create/restrict a dedicated Maps API key and save it as GitHub Actions secret MASHWARAK_MAPS_API_KEY.

Release tag:
  v1.13.0

No backend change is required. Backend stays V5.3.0. Distance x 7 EGP/km pricing is NOT implemented yet.

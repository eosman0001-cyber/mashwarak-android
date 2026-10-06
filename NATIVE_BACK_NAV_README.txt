MASHWARAK Android V1.11.2 | versionCode 20

Base: stable V1.11.1 Recovery.
Only functional change: Android system Back / edge swipe now asks the Mashwarak web UI to handle internal navigation first.
Expected sequence:
- Inner screen (e.g. Price List) -> open side drawer
- Back again from that drawer -> Customer Home
- Back from Customer Home -> exit app
- Active sheets/overlays close first

Requires the paired updated Index.html containing the mashwarak-native-back message handler.
No URL, Firebase, camera, notification, signing, or WebView focus changes.
Release tag suggestion: v1.11.2

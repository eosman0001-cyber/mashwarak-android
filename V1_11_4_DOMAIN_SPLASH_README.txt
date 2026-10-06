MASHWARAK ANDROID V1.11.4 - DOMAIN + HD SPLASH
================================================

Production target:
- versionName: 1.11.4
- versionCode: 22
- APP_URL: https://www.mshwarak.org/

Changes from V1.11.3:
1) APP_URL now opens the official Mashwarak custom domain instead of a direct Apps Script URL.
2) Splash screen now uses the official high-resolution gold/navy Mashwarak logo from brand assets.
   - Resource: app/src/main/res/drawable-nodpi/mashwarak_splash_logo.png
   - MainActivity no longer uses the 192px launcher icon for the splash.
3) WEB_UPDATE/Index.html.txt synced to the tested Customer Web V2.4.3 launch baseline.
4) Preserves V1.11.3 native-back-navigation fix and dynamic user-agent version.
5) Firebase configuration/package name unchanged.

Important:
- Do NOT replace the live Apps Script Index from an older WEB_UPDATE file.
- Current tested backend baseline remains V5.2.4.
- This source is intended to replace the older GitHub V1.11.1/code19 source.

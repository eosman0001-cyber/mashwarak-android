MASHWARAK V181 - Captain Activation UI

GITHUB: Upload only .github/ , app/ , gradle/ and the 4 root Gradle/config files. DO NOT upload APPS_SCRIPT_ONLY_DO_NOT_UPLOAD_TO_GITHUB folder.
APPS SCRIPT: First backup the current deployed Code.gs. Paste APPS_SCRIPT_ONLY_DO_NOT_UPLOAD_TO_GITHUB/Code.gs.txt into Code.gs, then deploy a new version of the SAME deployment. Index.html.txt is a reference snapshot only; DO NOT replace Index.html with it unless current version exactly matches.

CAUTION: This package was based on the V180 provided source. Do not overwrite newer independent changes to Code.gs without comparing.

Native OTP remains. UI is 3-step with responsive transitions. Password rule is simplified to >=8 chars with a short denylist, while the original backend server check remains binding. No APK build performed here.

Publish a tag greater than the most recent deployed tag; don't overwrite the same release. Validate on device.

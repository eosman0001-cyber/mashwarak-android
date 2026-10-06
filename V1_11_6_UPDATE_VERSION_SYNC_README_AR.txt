MASHWARAK ANDROID V1.11.6 - UPDATE VERSION SYNC FIX

سبب المشكلة في V1.11.5:
- مركز التحديث يقارن GitHub Release tag مع BuildConfig.VERSION_NAME داخل APK.
- تغيير خانة Release tag في Workflow كان ينشئ Release جديدًا فقط، لكنه لم يغير versionName/versionCode داخل APK.
- لذلك لو نُشر نفس APK 1.11.5 تحت tag مثل v1.11.6، التطبيق بعد التثبيت يظل داخليًا 1.11.5 ويرى v1.11.6 كتحديث جديد مرة أخرى.

الإصلاح في V1.11.6:
- release-apk.yml يستخرج MAJOR.MINOR.PATCH من Release tag.
- يمرر versionName وversionCode للبناء تلقائيًا.
- app/build.gradle.kts يقرأ القيم من بيئة GitHub Actions.
- Workflow يتحقق بعد البناء أن APK نفسه يحمل نفس versionName/versionCode قبل إنشاء Release.
- versionCode يُحسب بصورة متزايدة: major*1,000,000 + minor*1,000 + patch.

للنشر الآن:
1. ارفع محتويات هذه الحزمة إلى main.
2. Actions > Publish Mashwarak APK > Run workflow.
3. اكتب tag: v1.11.6
4. الـAPK الناتج سيحمل داخليًا versionName=1.11.6 وversionCode=1011006.
5. ثبته كتحديث فوق الإصدار الحالي.
6. بعد التثبيت افتح مركز التحديث واضغط تحقق الآن: يجب أن يظهر أنك تستخدم أحدث إصدار.

بعد هذا الإصلاح مستقبلاً:
- عند نشر v1.11.7 يكفي إدخال tag v1.11.7؛ الـWorkflow سيبني APK داخليًا كـ1.11.7 تلقائيًا.
- لا تستخدم tag أعلى لنفس APK القديم إلا من خلال الـWorkflow المعدل.

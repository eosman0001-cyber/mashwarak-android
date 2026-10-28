MASHWARAK V179 — FULL GITHUB READY SOURCE — 2026-10-28

هذه الحزمة جاهزة من جذر المستودع مباشرة، بلا مجلد وسيط.
عند فك الضغط تظهر app/ و .github/ و gradle/ في المسارات الصحيحة.

التعديلات المقصودة فقط:
1) app/src/main/AndroidManifest.xml: اسم التطبيق في Android Launcher هو Mashwarak.
2) app/src/main/java/com/jekonix/mashwarak/MainActivity.kt: زمن انتظار إخفاء شاشة الشعار من 450ms إلى 1800ms بعد onPageFinished، مع تجنب تنفيذ callback إذا أُغلقت Activity.

مراجعة المسار (دون تعديل وظيفي):
- CaptainActivationActivity يُجري Firebase OTP داخل Android Native.
- بعد نجاح OTP يجلب Firebase ID Token ويرسله إلى Apps Script في POST captain.firebase_activate عبر HTTPS.
- لا يُمرَّر OTP أو Firebase ID Token عبر JavaScript bridge.
- إنشاء كلمة المرور يتطلب سجل كابتن قائمًا في جدول Drivers كما تحقّق في التجارب السابقة.
- تسجيل الدخول اليومي بالكابتن يجري عبر الواجهة/Apps Script، وليس داخل CaptainActivationActivity.
- يجب اختبار التفعيل الفعلي للكابتن القديم، جلسة 12 ساعة، تحديث الاعتماد والمشاوير، وخطأ OTP الحقيقي 39؛ لا تعتبر هذه مثبتة بمجرد نجاح Build.

تركيب:
1. احفظ نسخة احتياطية من المستودع.
2. ارفع محتويات ZIP إلى جذر GitHub repo eosman0001-cyber/mashwarak-android مع Preserve paths.
3. شغّل Build Mashwarak APK ثم Publish Mashwarak APK برقم إصدار أعلى.
4. اختبر فتح التطبيق وتحديثه وملف الكابتن على هاتفك قبل التوزيع العام.
5. لا تغيّر Apps Script ولا تنشر ملفات TXT فوق ملفات Kotlin.

ملاحظات:
- مهلة 1800ms تحسن مظهر الإقلاع لكنها لا تعالج تلقائيًا فشل الشبكة أو iframe داخلي لم يكتمل.
- لم يتم تنفيذ Gradle build فعليًا محليًا لغياب Android SDK/Gradle؛ GitHub Actions هو اختبار البناء المطلوب.
- لم يتم تغيير Firebase Authentication أو خرائط Google أو الحجز أو الكاميرا أو الإشعارات.

مشوارك Android APK V1.0
========================

المشروع مبني بنفس فكرة تطبيق السلامة:
- WebView لتشغيل Google Sites.
- زر تحديث ثابت أعلى اليمين ليغطي منطقة علامة Google Sites.
- أيقونة التطبيق من صورة مشوارك المرفقة.
- JavaScript + DOM Storage + Cookies.
- رفع الصور / الكاميرا.
- روابط الاتصال tel: تفتح الاتصال مباشرة.
- إشعارات Firebase Cloud Messaging بصوت واهتزاز.
- كل الأجهزة تشترك تلقائيًا في topic: mashwarak_all.
- البناء المجاني عن طريق GitHub Actions.

قبل البناء:
1) رابط Google Sites تم تركيبه بالفعل:
   https://sites.google.com/view/mshwarak/mashwarak
2) أنشئ Firebase Project باسم Mashwarak.
4) أضف Android App بالـ package:
   com.jekonix.mashwarak
5) من Firebase Project Settings خذ:
   Project ID
   Web API Key
   App ID
   Project Number / Sender ID
   وضعهم في الملف.

البناء:
- ارفع فولدر المشروع بالكامل على GitHub repository.
- افتح Actions.
- Build Mashwarak APK.
- Run workflow.
- بعد النجاح افتح Artifacts > Mashwarak-APK.
- حمّل app-debug.apk.

الإشعار:
- التطبيق يشترك في mashwarak_all.
- Code.gs المرفق يرسل للإشعار لهذا الـtopic عند نشر إشعار من لوحة الإدارة.
- لتفعيل إرسال FCM من Apps Script يجب ضبط Service Account عبر الدالة:
  configureMashwarakFirebase(projectId, clientEmail, privateKey)
مرة واحدة فقط.

ملاحظة:
رابط Google Sites الخاص بمشوارك تم تركيبه بالفعل.
المتبقي فقط بيانات Firebase لتفعيل الإشعارات الصوتية.


تحديث V1.1
----------
- إصلاح بداية التطبيق أسفل شريط الساعة/الإشعارات في الهاتف.
- زر التحقق/التحديث أصبح مربعًا بحواف دائرية.
- تم تحريك زر التحديث إلى أعلى يمين مساحة Google Sites نفسها ليغطي زر المعلومات/التعجب.
- إضافة شاشة دخول / Splash بهوية مشوارك أثناء تحميل التطبيق.
- زر التحديث يعرض شاشة التحميل أثناء إعادة تحميل الموقع.


تحديث V1.2 | توقيع ثابت
-----------------------
- تم إعداد Release signing ثابت.
- GitHub Actions يبني app-release.apk موقّعًا.
- التحديثات المستقبلية يجب أن تستخدم نفس Keystore.
- إعدادات Firebase والإشعارات تبقى كما هي وسيتم ربط بيانات Firebase لاحقًا.


تحديث V1.3 | موضع زر التحقق
----------------------------
- نقل زر التحقق من أعلى يمين الشاشة.
- تثبيت الزر أسفل يسار الشاشة فوق علامة المعلومات/التعجب الخاصة بـ Google Sites.
- حجم الزر 50dp ومربع بحواف دائرية لتغطية العلامة بالكامل.
- الحفاظ على Safe Area وSplash والتوقيع الثابت بدون تغيير.


تحديث V1.4 | Firebase FCM
-------------------------
- ربط تطبيق مشوارك بمشروع Firebase: mashwarak-896b4
- Package: com.jekonix.mashwarak
- Sender ID: 327243985884
- تجهيز استقبال FCM على topic: mashwarak_all
- الإشعارات تستخدم قناة عالية الأهمية مع صوت واهتزاز.
- التوقيع الثابت وإعدادات GitHub Actions لم تتغير.
- الخطوة المتبقية: إعداد Service Account في Apps Script لإرسال الإشعارات من لوحة الإدارة.


تحديث V1.5
----------
- الضغط على Push Notification يفتح تطبيق مشوارك ثم يفتح قسم الإشعارات تلقائيًا.
- إضافة GitHub Release workflow لإنشاء رابط تحميل مباشر ثابت وآمن HTTPS.
- اسم ملف الإصدار في GitHub Releases: Mashwarak.apk
- Firebase والتوقيع الثابت محفوظان كما هما.


تحديث V1.6 | زر التحقق الديناميكي
---------------------------------
- زر التحقق أصبح يحدد موضعه تلقائيًا.
- RTL / العربية: أسفل اليمين فوق علامة Google Sites.
- LTR / الإنجليزية: أسفل اليسار فوق علامة Google Sites.
- بعد تحميل الصفحة يتم فحص اتجاه Google Sites نفسه.
- في حالة عدم توفر اتجاه واضح يتم استخدام اتجاه لغة الجهاز.
- Firebase والتوقيع الثابت والرابط المباشر محفوظون كما هم.


تحديث V1.7 | فتح الإشعارات مباشرة
----------------------------------
- تم إلغاء الاعتماد على الضغط الصناعي على زر الجرس.
- عند الضغط على Push Notification يفتح التطبيق رابط Apps Script المباشر:
  https://script.google.com/macros/s/AKfycbxqgdgey4Q7B5h5N7u-Slf-ShEIRpjUswkufJq9k3-TlCow_U4M3gbmSbsrweLngRwOhA/exec?open=notifications
- الواجهة تقرأ open=notifications وتفتح قسم الإشعارات تلقائيًا.
- فتح التطبيق من الأيقونة يظل على Google Sites كالمعتاد.
- Firebase والتوقيع الثابت والرابط المباشر محفوظون.


تحديث V1.7.1 | Build Fix
------------------------
- إعادة دوال تحديد موضع زر التحقق RTL/LTR التي حُذفت بالخطأ أثناء تعديل V1.7.
- الحفاظ على فتح الإشعارات عبر رابط Apps Script المباشر.
- لا تغيير في Firebase أو التوقيع الثابت أو رابط التحميل.


تحديث V1.7.2 | إصلاح فتح الإشعارات مع الحفاظ على Google Sites
---------------------------------------------------------------
- Google Sites يظل الواجهة الرئيسية دائمًا.
- تم إلغاء فتح Apps Script كرابط رئيسي عند الضغط على الإشعار.
- عند الضغط على الإشعار، يفتح Google Sites أولًا ثم يتم توجيه iframe الداخلي فقط إلى ?open=notifications.
- فتح التطبيق من الأيقونة يظل على Google Sites طبيعيًا.
- لا تغيير في Firebase أو التوقيع أو Safe Area أو Splash أو زر التحديث الديناميكي.

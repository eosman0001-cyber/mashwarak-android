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

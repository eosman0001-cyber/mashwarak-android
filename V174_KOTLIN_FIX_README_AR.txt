# مشوارك — إصلاح خطأ Kotlin في GitHub Actions

المشكلة: firebase-auth:25.0.0 تستخدم Kotlin metadata 2.4.0 بينما المشروع يبنى بـ Kotlin 2.2.0؛ لذلك يظهر خطأ :app:compileDebugKotlin.

## الأسهل والأكثر أمانًا (من غير رفع مشروع كامل)

في GitHub افتح `app/build.gradle.kts`، وغيّر سطر Firebase Auth فقط من:

`implementation("com.google.firebase:firebase-auth:25.0.0")`

إلى:

`implementation("com.google.firebase:firebase-auth:24.0.1")`

بعدها Commit؛ ستبدأ Actions من جديد. لو نجح البناء، نختبر التطبيق تجريبيًا فقط. هذه النسخة 24.0.1 موجودة في إصدارات Firebase الرسمية.

## مهم
- الملف `build.gradle.kts.txt` مرفق كاملًا لتسهيل النسخ عند الحاجة. لا ترفع TXT بهذا الاسم على GitHub؛ الاسم الفعلي في المشروع `app/build.gradle.kts`.
- لا تستبدل MainActivity.kt أو بقية ملفات المشروع إن كنت عدّلتها بالفعل.
- لا تضف مفاتيح توقيع أو أسرار Google في GitHub أو المحادثة.
- بناء GitHub لم يُختبر هنا؛ إصلاحنا يستهدف خطأ Kotlin المعروض فقط.
- النسخة الكاملة المرفقة مبنية على ملف المشروع الذي سبق رفعه بتاريخ 17 أكتوبر وملفات V174. قد تختلف عن آخر تعديل عندك على GitHub؛ لا ترفعها دفعة واحدة فوق المشروع الحالي إلا بعد مقارنة التغييرات.

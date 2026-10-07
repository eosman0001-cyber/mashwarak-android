مشوارك — V1.13.5 Places + Premium Home UX
تاريخ التجهيز: 08-10-2026

الإصدارات:
- Android: v1.13.5
- Customer Web: V2.6.2
- Backend Apps Script: V5.3.1

أهم التعديلات:
1) واجهة افتتاحية جديدة بهوية مشوارك وبزرين فقط:
   - اطلب مشوارك
   - انضمام كابتن
2) زر "اطلب مشوارك" يدخل إلى نفس نموذج الحجز الحالي مع الاحتفاظ بخطوات 1 / 2 / 3.
3) بحث الخريطة أصبح Google Places Autocomplete (New) داخل شاشة الخريطة نفسها:
   - اقتراحات مباشرة أثناء الكتابة.
   - النتائج مقيدة بمصر.
   - استخدام Session Token لكل جلسة بحث.
   - عند اختيار اقتراح يتم جلب الإحداثيات والعنوان وتحريك الخريطة للنقطة.
   - Android Geocoder يظل fallback إذا تعذر Places.
4) إصلاح Validation: عند وجود Lat/Lng دقيقة لا يتم طلب المحافظة/المركز/القرية.
5) بعد اختيار موقع دقيق تختفي الحقول اليدوية ويظهر كارت منظم للموقع مع "تعديل" و"اختيار يدوي".
6) الاختيارات اليدوية لم تُحذف؛ ما زالت fallback ويمكن الرجوع لها في أي وقت.
7) إزالة أي زيادة رقمية ثابتة لتشغيل التكييف. يظهر فقط تنبيه بأن تشغيل التكييف قد يضيف تكلفة بسيطة ويتم تأكيدها قبل تنفيذ المشوار.
8) لم يتم إضافة تسعير المسافة أو Routes في هذا الإصدار.

مهم قبل بناء Android:
- لا تنشئ API Key جديدًا.
- استخدم نفس GitHub secret: MASHWARAK_MAPS_API_KEY
- في Google Cloud فعّل Places API (New).
- عدّل API restrictions لنفس المفتاح لتسمح بخدمتين فقط:
  * Maps SDK for Android
  * Places API (New)
- اترك Application restriction كما هي للتطبيق:
  Package: com.jekonix.mashwarak
  Release SHA-1: 86:E5:D9:52:D9:4C:F6:16:AA:88:03:3D:90:44:C1:06:3D:7B:F4:91

Apps Script:
- استبدل Index.html بمحتوى WEB_UPDATE/Index.html.txt
- استبدل Code.gs بمحتوى WEB_UPDATE/Code.gs.txt
- احفظ ثم اعمل Deploy إصدار جديد.

GitHub:
- ارفع محتويات هذا المشروع إلى repository كما اعتدت.
- انتظر نجاح Build العادي.
- Actions > Publish Mashwarak APK > Run workflow
- Tag: v1.13.5

ملاحظة تقنية:
Places SDK مثبت على 5.1.1 عمدًا في هذا الإصدار لأن كود V1.13.5 مبني ومثبت على واجهات تلك السلسلة. لا تعمل ترقية تلقائية لمكتبة Places أثناء اختبار هذا الإصدار.

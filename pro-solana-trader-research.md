# خوارزمية Pro محافظة وقابلة للتفسير لعملات الميم على Solana

## Pump.fun وRaydium — مع مسار PumpSwap الحديث

> **الغرض:** تحويل خمس دراسات خاصة بعناوين/متداولين إلى نظام فحص وتنفيذ ورقي محافظ، وليس نسخ سلوكهم أو ادعاء امتلاك ميزة ربحية مؤكدة.
>
> **قاعدة حاكمة:** كل قاعدة في هذا التقرير مصنفة إما **دليل مباشر** من البيانات المقدمة، أو **استنتاج تشغيلي مقترح**. لا يجوز تقديم الاستنتاج المقترح على أنه قاعدة كان يتبعها أي متداول.

---

## 1) الخلاصة التنفيذية

الخوارزمية المقترحة ليست "sniper" يشتري أول بلوك، وليست نظام نسخ محافظ. إنها **بوابة مخاطر متعددة المراحل**:

1. اكتشاف الإصدار وتثبيت الـ mint والوقت والبرنامج.
2. تحديد ما إذا كان الأصل على **منحنى Pump.fun** أو في **تجمع AMM بعد الترحيل**.
3. رفض الإصدارات التي لا يمكن التحقق من سيولتها، أو التي تظهر تنسيقاً مبكراً، أو تركّزاً خطراً.
4. الدخول فقط بعد ظهور طلب متنوع وسيولة قابلة للخروج، وبحجم صغير.
5. تأكيد الترحيل بتوقيع on-chain وبعنوان التجمع، لا بمجرد صفحة أو ارتفاع السعر.
6. إدارة مركز spot فقط، مع أرباح جزئية، وحدود خسارة وهيكل خروج واضح.
7. تسجيل كل قرار في **Paper Trading** بنفس شكل أمر حقيقي، لكن دون توقيع أو بث معاملة.

### ما الذي نتعلمه فعلاً من الدراسات؟

- **paulo.sol:** أفضل دليل توقيت علني يدعم الدخول بعد صعود وظهور السيولة/الاهتمام في BONK وWIF وBODEN، لا شراء أول لحظة. مع ذلك، معاملة FIRST تُظهر أن العنوان كان مستلماً غير موقّع في معاملة شراء مزدوجة؛ لا يجوز اعتبارها دليلاً على قرار دخول مستقل.
- **naseem:** توجد تقارير عن دخول سريع جداً ومحافظ مرتبطة، لكن العنوان المفحوص نفسه أظهر في العينة الحديثة توزيعات مكافآت holders، لا تداولاً. لا يجوز نسخه أو اعتباره sniper مثبتاً.
- **traderpow:** صفقة PASTERNAK/LAUNCHCOIN موثقة كحالة تركّز وسحب جزئي بعد إعادة تسعير سردية؛ لا توجد قواعد منشورة للسيولة أو الإيقاف.
- **shatter.sol:** خروج BOME الموثق كان سريعاً وكاملاً بعد ربح كبير؛ لكنه كان شراء presale لا دليلاً على دخول Pump.fun. تقارير الشبكات المرتبطة لا تثبت سيطرة العنوان على كل المحافظ.
- **HWdeC:** الادعاءات التجميعية عن FARTCOIN متعارضة وغير كافية لإثبات دخول/خروج أو قواعد هذا العنوان. الأدبيات تشير إلى أن غالبية الإطلاقات لا تتخرج، وأن النشاط الآلي المبكر قد يرتبط باحتمال تخرج مشروط أقل.

**النتيجة:** نأخذ من الحالات إشارات عن أهمية التحقق والانتقائية والخروج الجزئي، ونرفض تقليد السرعة العمياء، التركيز المفرط، تحمل انهيار 99%، أو الاعتماد على اسم/تصنيف محفظة.

---

## 2) سياسة الدليل والحدود

### درجات الدليل

| الدرجة | المعنى | مثال من الدراسات |
|---|---|---|
| A — مباشر | توقيع/تعليمة/وقت/عنوان يمكن فحصه on-chain | معاملة FIRST، خروج BOME، تعليمات Pump.fun |
| B — تقاطع علني | أكثر من مصدر يربط الاسم بالعنوان، دون دفتر كامل | ربط paulo.sol وtraderpow وshatter.sol |
| C — استنتاج | قاعدة مخاطرة صممتها الخوارزمية استناداً إلى الأنماط | حد تركّز، حد انزلاق، تأخير تأكيد |
| D — غير مثبت | لا يدخل قراراً آلياً | ادعاء insider أو قاعدة stop خاصة بمحفظة |

### محظورات منهجية

- لا نستخدم PnL منشوراً أو ترتيباً تسويقياً كدليل على ميزة مستقبلية.
- لا نخلط لقطة حالية من Pump.fun أو Solscan مع حالة تاريخية وقت الإطلاق.
- لا نعدّ المستلم غير الموقّع متداولاً مستقلاً.
- لا نستنتج أن رمزاً يحمل لاحقة `pump`، أو أن له pool، بأنه آمن.
- لا نخلط **Raydium التاريخي** (قبل 20 مارس 2025) مع **PumpSwap الحالي** (منذ إطلاقه في 20 مارس 2025).
- لا نستخدم أي قيمة حالية للسعر/السيولة كأنها backtest.

---

## 3) تعريف الحالات — State Machine

لكل mint سجل واحد يمر بالحالات التالية:

```text
DISCOVERED
  -> VERIFIED_MINT
  -> CURVE_ACTIVE
  -> MIGRATION_PENDING
  -> MIGRATION_CONFIRMED
  -> AMM_ACTIVE
  -> EXITING
  -> CLOSED

أي حالة
  -> REJECTED / PAUSED / RUG_EXIT
```

### 3.1 `DISCOVERED`

مصادر الاكتشاف المسموحة:

- حدث `create` أو `create_v2` من برنامج Pump.fun.
- mint جديد مع metadata قابلة للقراءة، creator، slot، block time، signature.
- إشعار موثوق من فهرس/stream، بشرط تأكيده من RPC.

لا يُسمح بأمر شراء من رابط اجتماعي أو اسم محفظة فقط.

### 3.2 `CURVE_ACTIVE`

نثبت أن:

- الحساب/المنحنى مرتبط بالـ mint نفسه.
- `complete = false`.
- نقرأ الاحتياطي الافتراضي/الحقيقي، SOL الداخل، tokens المباعة، وعدد الصفقات.
- تعليمات التداول هي `buy`/`sell` الخاصة ببرنامج Pump.fun، لا تحويلات عادية أو airdrop.

**الفصل المهم:** نسبة تقدم المنحنى ليست جودة. تقدم سريع مع عدد مشترين قليل أو ممولين مشتركين قد يكون تنسيقاً.

### 3.3 `MIGRATION_PENDING`

تُستخدم عند أحد المؤشرات التالية:

- `complete = true` أو بلوغ threshold/احتياطي التخرج.
- ظهور معاملة migration مرتبطة بالـ mint.
- توقف تسعير curve وظهور مؤشرات pool متوقعة.

لا نشتري أثناء فجوة غير مؤكدة بين المنحنى والتجمع.

### 3.4 `MIGRATION_CONFIRMED` و`AMM_ACTIVE`

يلزم تخزين:

- توقيع الترحيل النهائي وslot/time.
- عنوان pool، mint base، quote mint (عادة SOL/WSOL)، وDEX.
- احتياطي base/quote عند الإنشاء وبعده.
- حالة LP/burn إن أمكن التحقق منها؛ لا يكفي ادعاء "liquidity locked".
- هل المسار تاريخي Raydium أم حديث PumpSwap.

**قاعدة الزمن:**

- إطلاق قبل 20-03-2025: إذا تخرج، نبحث عن مسار Raydium التاريخي.
- إطلاق من 20-03-2025 فصاعداً: المسار الطبيعي PumpSwap، ولا نفترض Raydium.
- لا نسمّي تجمعاً Raydium إلا إذا أكدته instruction/pool on-chain.

---

## 4) بوابة الاكتشاف والدخول

### 4.1 سجل الاكتشاف الإلزامي

قبل أي قرار، ينشئ النظام `LaunchRecord`:

```json
{
  "mint": "<base58>",
  "creator": "<base58>",
  "created_at": "<UTC ISO-8601>",
  "created_slot": 0,
  "create_signature": "<signature>",
  "venue": "pumpfun",
  "metadata_uri": "<uri or null>",
  "curve_account": "<base58 or null>",
  "source_confidence": "A|B|C"
}
```

إذا تعذر تثبيت `mint + creator + signature` من RPC، فالقرار `REJECTED`.

### 4.2 سياسة توقيت محافظة

لا ندخل في أول بلوك افتراضياً. ننتظر **نافذة تحقق**:

- لا شراء قبل مرور 30 ثانية من وقت الإنشاء، إلا إذا كانت سياسة Paper Trading منفصلة تختبر فرضية first-block دون أموال.
- لا دخول حقيقي مقترح قبل توفر **10 صفقات ناجحة على الأقل** و**5 مشترين فريدين** و**3 بائعين أو دليل خروج قابل للاختبار**؛ هذه حدود سياسة مقترحة وليست قواعد متداولين.
- نقطة أفضلية مبدئية: بين 60 ثانية و10 دقائق، عندما يثبت تدفقاً متنوعاً ولا يكون المنحنى قد اقترب من الانتقال دون سيولة خروج.
- نفضّل **الدخول بعد التأكيد** على شراء كل إطلاق؛ الدراسات نفسها لا تثبت أن العناوين الخمسة ربحت باستراتيجية أول ثانية.

إذا كانت الحركة قد ارتفعت بعنف بينما عدد المشترين الفريدين منخفض، نرفض مطاردة السعر.

### 4.3 حد أدنى للسيولة — مقترح محافظ

نحوّل كل السيولة إلى SOL وUSD لحظة القياس، ونستخدم أقل قيمة موثوقة بين المصدرين:

- على المنحنى: `virtual_quote_reserve >= 30 SOL` **و** `estimated_exit_depth >= 10 × intended_position_value`.
- بعد AMM: `min(base_reserve_value, quote_reserve_value) >= 50,000 USD` للاختبار الورقي الأساسي، أو `>= 20 × intended_position_value` أيهما أشد.
- لا دخول إذا كانت القيمة المعروضة `0`، أو متناقضة جذرياً بين الفهارس، أو لا تسمح بمحاكاة بيع 25% من المركز بانزلاق مقبول.
- هذه ليست دعوى عن عتبات paulo.sol أو أي عنوان؛ إنها **فلتر حماية** يمكن تخفيفه فقط في وضع اختبار مصنف عالي المخاطر.

السيولة ليست market cap. نرفض استخدام market cap وحده كضمان خروج.

---

## 5) تحليل تدفق الشراء والبيع

نحسب على نوافذ 30 ثانية، 2 دقيقة، 5 دقائق، ثم من وقت الترحيل:

```text
buy_volume_sol
sell_volume_sol
net_flow = buy_volume_sol - sell_volume_sol
unique_buyers
unique_sellers
buy_to_sell_count
median_trade_size
largest_trade_share
new_buyer_retention_5m
```

### بوابة التدفق

يمر الأصل إذا تحققت كل الشروط التالية في نافذة التقييم:

1. `net_flow > 0` في نافذتين متتاليتين، لا في لقطة واحدة.
2. `unique_buyers >= 10` على الأقل في الإصدار الأولي، ويفضل أن يكون نموهم مستمراً.
3. لا يتجاوز أكبر مشترٍ 20% من حجم الشراء في النافذة، إلا إذا كانت المعاملة موثقة كصانع سوق وليس holder.
4. يوجد نشاط بيع طبيعي؛ غياب البائعين ليس بالضرورة قوة، وقد يعني حبساً أو عدم اختبار قابلية الخروج.
5. لا تعتمد الإشارة على 2–3 محافظ متكررة أو على تحويلات داخلية.
6. بعد الترحيل، نعيد الحساب من الصفر؛ تدفق المنحنى لا ينتقل آلياً إلى جودة AMM.

**رفض فوري:** sell burst من creator أو أكبر holders، أو net flow سالب مع هبوط عمق التجمع، أو buys متكررة ثم sells خلال ثوانٍ من نفس مجموعة الممولين.

---

## 6) مخاطر sniper / bundle / holder concentration

لا نصف العنوان بأنه insider أو manipulator بلا إثبات. نستخدم **مؤشرات قابلة للتفسير**:

### 6.1 مؤشرات التنسيق

نسجل:

- same-slot أو same-block buys.
- فرق الثواني بين create وأول buy.
- priority fee غير اعتيادي.
- ممول مشترك أو تمويل متسلسل.
- محافظ تشتري نفس الكمية تقريباً ثم تبيع متزامنة.
- عنوان مستلم لا يوقع المعاملة — مثل معاملة FIRST — يصنّف `recipient/bundle-like`, وليس trader.
- علاقة creator بالشراء الأول أو accounts التي أنشأها creator.

### 6.2 درجات الخطر

| الدرجة | شروط إرشادية | الفعل |
|---|---|---|
| أخضر | لا ممول مشترك ظاهر، أول 20 مشترٍ متنوعون، لا sell burst | يسمح بفحص الدخول |
| أصفر | 20–40% من الشراء الأول من مجموعة مترابطة، أو same-slot واضح | نصف الحجم الورقي ولا دخول حي |
| أحمر | أكثر من 40% من أول الشراء من مجموعة مشتركة، أو creator/linked wallets تملك/تبيع بكثافة، أو buy ثم sell متزامن | رفض |

هذه النسب **سياسة مقترحة** وليست عتبات مصدرية.

### 6.3 تركّز الحيازة

نحسب بعد استبعاد LP/burn والـ system accounts مع توثيق الاستبعاد:

- `top_10_share` و`top_20_share`.
- creator share.
- share لأول 100 مشترٍ.
- share للمحافظ المرتبطة بالممولين.

عتبات الدخول المقترحة:

- `top_10_share <= 35%`، وcreator `<= 5%`، وإلا رفض/انتظار.
- `top_20_share <= 50%` أفضل؛ فوق 60% رفض.
- بعد الترحيل، نعيد القياس لأن توزيع LP والتوكنات قد يتغير.
- إذا كان creator أو مجموعة مرتبطة تزيد حيازتها أثناء صعود السعر، ننتظر أو نخرج.

عدد holders وحده لا يكفي؛ يمكن إنشاء عشرات المحافظ بتنسيق واحد.

---

## 7) تأكيد الترحيل — لا ثقة بالواجهة

### شرط التأكيد الآلي

`migration_confirmed = true` فقط عند اجتماع:

1. اكتمال curve أو تحقق threshold في حالة الحساب.
2. وجود signature ترحيل، أو سلسلة تعليمات موثقة تنشئ pool للـ mint.
3. مطابقة `mint` في pool base و`SOL/WSOL` في quote.
4. قراءة احتياطي أولي غير صفري من الحسابات الفعلية.
5. توفر event/order quote قابل للمحاكاة، وليس صفحة سعر فقط.

إذا غاب أي شرط، الحالة `MIGRATION_PENDING`، ولا نحتسب ربحاً محققاً.

### قاعدة عدم المطاردة

لا ندخل خلال أول كتلة بعد الترحيل. ننتظر:

- معاملتي swap على الأقل في كل اتجاه إن أمكن.
- تحديث احتياطيات مرتين.
- التأكد أن liquidity ليست مجرد وديعة أولية يسحبها creator.
- إعادة فحص holders، sell flow، slippage، وLP status.

يمكن فتح مركز ورقي عند migration confirmation، لكن المركز الحي المقترح يحتاج عمقاً إضافياً.

---

## 8) الانزلاق والتنفيذ

### 8.1 تقدير قبل الأمر

لكل أمر نحسب:

```text
expected_out = quote(order_size, reserves, fee)
price_impact = 1 - (expected_out / mid_price_out)
all_in_cost = price_impact + dex_fee + priority_fee + safety_buffer
```

نستخدم quote من الحالة الأقرب للـ slot، ونرفض quote الأقدم من 2 ثانية في مرحلة سريعة أو 10 ثوانٍ في AMM مستقر.

### 8.2 الحدود المقترحة

- منحنى: `max_slippage = 3%` للشراء، `5%` للبيع.
- AMM عميق: `max_slippage = 1.5%` للشراء، `2.5%` للبيع.
- AMM رقيق/مرحلة ترحيل: `max_slippage = 3%` للشراء، `5%` للبيع، مع خفض الحجم؛ إذا تجاوزت المحاكاة ذلك، لا نوسع الحد بل نصغر الأمر.
- لا نرسل أمر market غير محدود.
- نقسم الأمر إلى 2–4 دفعات فقط إذا كانت كل دفعة تعيد حساب الاحتياطي؛ التقسيم ليس وسيلة لتجاوز فلتر المخاطر.

**فشل التنفيذ:** إذا تغيّر quote أكثر من 25% بين التقييم والبث الورقي، نلغي ونعود إلى `REQUOTE`; لا نملأ بسعر قديم.

---

## 9) حجم المركز والدخول

### 9.1 لا رافعة

- **Spot فقط، دون leverage، دون اقتراض، دون perpetuals، ودون liquidation risk.**
- كل مركز مخاطرة مستقلة؛ لا نعوض خسارة بزيادة الحجم.
- الحد الأقصى للمخاطرة في الصفقة: `0.25%` من رأس مال Paper Trading الافتراضي حتى تثبت البيانات؛ سقف التعرض الاسمي `1%` للمركز الأول.
- إجمالي تعرض كل عملات الميم: `5%` من رأس المال الافتراضي، وإجمالي التعرض لنفس creator/cluster: `1%`.
- إذا كان السعر غير مستقر، نخفض الحجم كي يبقى **خطر الخروج** ضمن الحد، لا كي نملأ نسبة ثابتة من المحفظة.

### 9.2 نموذج نقاط قابل للتفسير

```text
score = 0
+2  mint/creator/signature مؤكدة من RPC
+2  curve أو pool state مؤكدة
+2  سيولة >= الحد وعمق الخروج >= 10x حجمنا
+2  net flow موجب في نافذتين مع مشترين متنوعين
+1  top-10 والتركيز ضمن الحدود
+1  لا creator sell ولا bundle red flags
+1  migration مؤكد (للدخول بعد الترحيل)
-3  same-funder/same-slot cluster أحمر
-3  creator أو holder كبير يبيع
-2  quote/slippage غير قابل للتنفيذ
-2  تناقض indexers أو بيانات قديمة
```

- دخول منحنى: يلزم `score >= 7` دون أي `-3`.
- دخول بعد AMM: يلزم `score >= 8` مع migration مؤكد.
- `score < 7`: `WATCH` فقط.
- أي rug/creator sell حرج: `REJECT` مهما كانت النقاط.

النقاط لا تعني توقع عائد؛ تعني أن البيانات تسمح بتجربة صغيرة قابلة للخروج.

### 9.3 تقسيم الدخول

- `Entry-1`: 40% من الحجم المسموح بعد اجتياز البوابة.
- `Entry-2`: 30% فقط بعد 2–5 دقائق من ثبات التدفق وعدم ازدياد التركّز.
- `Entry-3`: 30% فقط بعد كسر قمة محلية مع volume وunique buyers، أو بعد تأكيد migration واستقرار quote.
- إذا لم يتحقق الشرط، لا نكمل الحجم. **عدم إضافة المال ليس فشلاً.**

---

## 10) الخروج الجزئي، الإيقاف، والرug response

### 10.1 خطة خروج افتراضية

الخطة التالية مقترحة للحساب الورقي وليست منسوبة للمتداولين:

- عند `+1R`: بيع 25% واسترجاع جزء من المخاطرة.
- عند `+2R`: بيع 25% أخرى.
- عند `+3R` أو تضاعف من متوسط التكلفة: بيع 25%.
- اترك 25% فقط كـ runner، مع trailing من القمم.
- إذا انهار الحجم/السيولة، نبيع ما يمكن تنفيذه؛ لا ننتظر هدفاً سعرياً.

حيث `R` هو الخطر النقدي المحدد قبل الدخول، بعد احتساب الانزلاق، لا مجرد فرق سعر.

### 10.2 إيقاف هيكلي

نخرج تدريجياً أو كلياً عند أول تحقق قوي من الآتي:

- creator أو مجموعة مرتبطة تبيع فوق حد الإنذار.
- فقدان أكثر من 30% من عمق الخروج أو سحب سيولة غير مفسر.
- sell volume يتجاوز buy volume في نافذتين مع فشل السعر في الاسترداد.
- فشل migration أو عدم تطابق pool/mint.
- هبوط `top_10_share` ظاهرياً بسبب توزيع/تحويل غير واضح أو ارتفاع تركّز المجموعة.
- كسر دعم ما بعد الدخول مع وصول الخسارة إلى `-1R`.
- عدم القدرة على تنفيذ بيع 25% ضمن `max_slippage`.

### 10.3 سياسة drawdown

- عند `-0.5R`: لا تضف مركزاً؛ راقب فقط.
- عند `-1R`: خروج 50% أو أكثر حسب العمق.
- عند `-1.5R`: إغلاق ما تبقى ما لم يكن السبب خطأ quote موثقاً يمكن تصحيحه فوراً.
- **لا نسمح بحالة 99% drawdown** باعتبارها "conviction". حالة traderpow الموصوفة بانخفاض يقارب 99% قبل انتعاش سردي هي failure pattern، وليست قاعدة تداول.

### 10.4 استجابة rug/مخاطر كارثية

```text
RUG_ALERT:
  freeze_new_entries = true
  cancel_pending_buys = true
  recompute_sell_quote = true
  attempt_small_exit = true
  if executable: scale out 25% -> 25% -> remainder
  if not executable: mark UNEXITABLE; do not fabricate fill
  quarantine creator/cluster/mint
  increase global risk lockout
```

لا نرفع slippage بلا حد لإنقاذ صفقة. نُسجل `UNEXITABLE` كخسارة/فشل تنفيذ واقعي، مع فصلها عن PnL السعر النظري.

---

## 11) Paper Trading — تكامل دقيق وآمن

> **المقصود بـ Paper Trading هنا:** محرك محاكاة يحافظ على نفس بيانات القرار، quote، fill، الرسوم، الانزلاق، latency، وتغير الحالة التي سيستخدمها محرك حي؛ لكنه لا يملك مفتاحاً ولا يوقّع ولا يبث معاملة.

### 11.1 واجهة البيانات المطلوبة

```text
MarketDataAdapter
  get_launches(from_slot, to_slot) -> LaunchRecord[]
  get_curve_state(mint, slot) -> CurveState
  get_trades(mint, from_slot, to_slot) -> Trade[]
  get_holders(mint, slot) -> HolderSnapshot
  get_migration(mint) -> MigrationRecord | null
  get_pool_state(pool, slot) -> PoolState
  quote_buy(venue, mint, amount_in, slot) -> Quote
  quote_sell(venue, mint, amount_in, slot) -> Quote
```

### 11.2 أمر Paper Trading القياسي

```json
{
  "paper_order_id": "uuid",
  "run_id": "uuid",
  "created_at": "UTC ISO-8601",
  "decision_slot": 0,
  "mint": "<base58>",
  "venue": "pumpfun_curve|raydium|pumpswap",
  "side": "buy|sell",
  "order_type": "limit_or_bounded_market",
  "amount_in": 0.0,
  "amount_unit": "SOL|token",
  "max_slippage_bps": 300,
  "quote_signature": "<local quote id>",
  "strategy_score": 0,
  "risk_flags": [],
  "decision": "ENTER_1|ENTER_2|ENTER_3|TP_1|TP_2|TP_3|STOP|RUG_EXIT|REJECT",
  "fill_model": "next_observable_quote",
  "status": "simulated|cancelled|rejected|unexecutable",
  "expected_out": 0.0,
  "filled_out": 0.0,
  "effective_slippage_bps": 0,
  "fees_sol": 0.0,
  "reason": "Arabic/English explainable reason"
}
```

### 11.3 قواعد المحاكاة الدقيقة

1. **تأخير:** لا نستخدم بيانات المستقبل؛ الأمر يرى آخر state قبل `decision_slot`، ويُملأ عند أول quote لاحق قابل للتنفيذ.
2. **انزلاق:** نخصم `price_impact + fee + priority_fee + latency_buffer` من fill، وليس من السعر النظري فقط.
3. **السيولة:** إذا كان بيع 25% من المركز يتجاوز `max_slippage_bps`، الحالة `unexecutable`، لا fill اصطناعي.
4. **التجزئة:** يعاد تحديث reserves بعد كل fill جزئي.
5. **التخرج:** عند transition لا ننقل سعر curve إلى pool؛ نغلق quote القديم ونطلب quote جديد من pool بعد التأكيد.
6. **الرسوم:** نسجل DEX fee، priority fee المقدرة، وأي rent/ATA cost إن كان منطبقاً؛ لا نخفيها في PnL.
7. **P&L:**
   ```text
   realized_pnl = proceeds_sol - cost_basis_sold - fees_sol
   unrealized_pnl = marked_value_at_executable_bid - remaining_cost_basis
   net_pnl = realized_pnl + unrealized_pnl - all_recorded_fees
   ```
8. **لا transfer يعتبر fill:** airdrop أو recipient account أو holder reward لا يدخل كشراء؛ يُسجل `source=airdrop/reward` منفصلاً.
9. **إعادة التشغيل:** كل قرار deterministic من `run_id + data_cutoff_slot + config_hash`.
10. **لا live bridge:** Adapter التنفيذ في Paper Trading يجب أن يرفض أي `sign_transaction`, `send_raw_transaction`, leverage أو borrow call.

### 11.4 الحد الأدنى لجدول السجل

| الجدول | الحقول الأساسية |
|---|---|
| `launches` | mint, creator, create signature, slot/time, metadata |
| `curve_snapshots` | mint, slot, complete, vSOL, real reserves, progress |
| `trades` | signature, slot, buyer/seller, side, SOL, tokens, fee |
| `holder_snapshots` | slot, top10/top20, creator, linked-cluster shares |
| `migrations` | mint, signature, venue, pool, slot/time, reserves |
| `paper_orders` | order JSON أعلاه، status، fill، slippage، reason |
| `risk_events` | type, severity, evidence signatures، action |
| `equity_marks` | timestamp/slot, cash, inventory executable bid, PnL |

### 11.5 واجهة تشغيل مقترحة

```text
paper start --config conservative-v1 --from-slot S --to-slot T
paper replay --run RUN_ID --mint MINT
paper explain --order ORDER_ID
paper export --run RUN_ID --format jsonl
paper promote --config conservative-v1 --require-approval
```

`paper promote` لا يفعّل تداولاً حياً؛ هو فقط ينقل config إلى مراجعة بشرية. أي تنفيذ حي، إن أضيف لاحقاً، يجب أن يكون Adapter منفصلاً ومقفلاً افتراضياً وبمفاتيح وصلاحيات مستقلة.

### 11.6 معايير قبول قبل أي تجربة حية

- لا نستخدم نتيجة على عملة واحدة.
- نحتاج عينة لا تقل عن 200 إشارة مكتملة أو 30 يوماً من البيانات، مع فصل زمني out-of-sample.
- نسجل نسبة الأوامر غير القابلة للتنفيذ، لا win rate فقط.
- نحلل PnL بعد fees/slippage، median trade، worst drawdown، tail loss، exposure، وcluster losses.
- نعيد الاختبار مع تأخير 1–3 ثوانٍ ومع quotes أسوأ؛ إذا انهارت النتيجة، فالاستراتيجية تعتمد على سرعة غير قابلة للتقليد.
- لا ننتقل من Paper Trading إلى real-money لمجرد أن headline PnL موجب.

---

## 12) رفض النسخ الأعمى والرافعة

### ممنوعات صريحة

- **لا نسخ لمحفظة** عبر اسم paulo.sol أو naseem أو traderpow أو shatter.sol أو HWdeC.
- لا نشتري لأن محفظة مشهورة اشترت؛ نتحقق من mint، توقيع الشراء، وقت الشراء، القابلية الحالية للخروج، والـ cluster.
- لا نعتبر عنواناً غير موقّع في معاملة دليلاً على قرار المتداول.
- لا نستخدم leverage أو قرضاً أو perpetual أو margin.
- لا نضاعف مركزاً خاسراً لمجرد أن المتداول الأصلي احتمل خسارة كبيرة.
- لا نستخدم first-block/priority fee كميزة مفترضة دون قياس latency ونتائج بعد الانزلاق.
- لا نعتبر migration أو Raydium/PumpSwap شهادة أمان.

### سبب الرفض

النسخ يصل متأخراً، يدفع سعراً مختلفاً، قد يواجه partial fills، ولا يعرف ما إذا كانت الصفقة المرئية شراء مستقل، allocation، airdrop، bundle، أو نقل بين محافظ. كما أن البيانات المقدمة نفسها تحوي اختلافات كبيرة بين snapshots وP&L، وتحذيرات من attribution غير المؤكد.

---

## 13) بروتوكول قرار مختصر

```text
on create:
  verify mint/creator/signature from RPC
  if not verified: REJECT
  wait validation window
  read curve, flow, holders, funding graph
  if creator sell, concentration red, or exit depth insufficient: REJECT
  if score < 7: WATCH
  else: paper ENTER_1 with bounded slippage

on curve update:
  recompute flow, holders, depth, score
  if complete/threshold: MIGRATION_PENDING
  if rug flags: RUG_EXIT
  if score remains valid: optionally ENTER_2/ENTER_3

on migration candidate:
  require signature + matching pool + nonzero reserves + fresh quotes
  otherwise remain MIGRATION_PENDING
  if confirmed: close curve quote; re-underwrite AMM
  if AMM score >= 8: paper post-migration entry only if depth allows

on position:
  simulate TP at +1R/+2R/+3R
  stop on structural invalidation, not hope
  cap all exposure; never leverage

on exit:
  use executable bid and real slippage
  if unexecutable: record failure, do not invent price
  close and quarantine bad creator/cluster
```

---

## 14) مقاييس التفسير والمراجعة

كل قرار يجب أن يجيب في سجل واحد:

1. ما mint والـ signature والـ slot؟
2. هل هو curve أم Raydium أم PumpSwap؟ وما الدليل؟
3. كم السيولة القابلة للخروج عند حجمنا؟
4. ما عدد المشترين/البائعين الفريدين؟ وهل التدفق موجب في نافذتين؟
5. ما `top_10_share`, creator share، ومؤشر الممول المشترك؟
6. ما سبب score؟ وما سبب كل خصم؟
7. ما quote، slippage، fee، وlatency المفترضة؟
8. ما شرط invalidation والخروج؟
9. هل fill قابل للتنفيذ أم نظري؟
10. هل القرار مستند إلى دليل A/B أم استنتاج C؟

إذا لم يمكن الإجابة، القرار `WATCH/REJECT` وليس `BUY`.

---

## 15) مصادر البيانات الواردة في الدراسات

المصادر الأصلية المذكورة في المادة تشمل:

- [Solscan — paulo.sol account](https://solscan.io/account/DcgVuxrYHCZQUtLgYSddqL12FKYa2qnWcGGyb1H79thc) و[معاملة FIRST](https://solscan.io/tx/39NzZWgBFjXZPRXmfJWLGxUCjsipRde3AsTyWwrS3MgDwdJC2CuhGt5w9ZPoy1g3sKBqzD3WKz1kpTW11ka6oXJV).
- [Pump.fun bonding curve](https://pump.fun/docs/bonding-curve) و[create coin](https://pump.fun/docs/create-coin).
- [Chainstack — Pump.fun migrations](https://docs.chainstack.com/docs/solana-listening-to-pumpfun-migrations-to-raydium).
- [Bitquery — Pump.fun/PumpSwap lifecycle](https://docs.bitquery.io/docs/blockchain/Solana/Pumpfun/pump-fun-to-pump-swap/).
- [Solscan — 5CP6 / Naseem](https://solscan.io/account/5CP6zv8a17mz91v6rMruVH6ziC5qAL8GFaJzwrX9Fvup) ومعاملة التوزيع المشار إليها في الدراسة.
- [The Block — TRUMP attribution caveats](https://www.theblock.co/news/web3/crypto-analytics-platform-bubblemaps-claims-one-trader-turned-1-million-into-109-million-trading-trump-memecoin-341664).
- [Arkham — traderpow case study](https://info.arkm.com/research/traderpow-makes-2-4m-on-launchcoin) و[Dune](https://dune.com/queries/4838225).
- [Nansen — shatter.sol](https://nansen.ai/post/top-10-memecoin-wallets-to-track-for-2025) و[معاملة BOME](https://solscan.io/tx/5JRznMKWso1RimWB9qW59Bc8eFMPdhcZW1MMG2wkTC7ABTvgEQ28URANymMsZyts2U3jSotKJzBcNzjRhwPmmoHB).
- [Nansen — wallet research](https://nansen.ai/post/how-to-use-nansen-to-find-profitable-solana-wallet-addresses) و[Solscan — HWdeC](https://solscan.io/account/HWdeCUjBvPP1HJ5oCJt7aNsvMWpWoDgiejUWvfFX6T7R).
- [Dune — Pump.fun sniper methodology](https://dune.com/webacyddxyz/solana-sniper-detection-pumpfun).
- [Pump public docs — program](https://github.com/pump-fun/pump-public-docs/blob/main/docs/PUMP_PROGRAM_README.md).

---

## 16) الخلاصة

الخوارزمية المحافظة لا تحاول إثبات أن أي متداول من الخمسة يملك وصفة قابلة للنسخ. إنها تستخلص ما يمكن الدفاع عنه: **تحقق on-chain، انتظار تأكيد، سيولة قابلة للخروج، تنوع تدفق، حذر من التنسيق، فصل curve عن AMM، تأكيد الترحيل، أوامر محدودة الانزلاق، أرباح جزئية، وإيقاف هيكلي**.

المخرَج الصحيح قد يكون `NO TRADE` في معظم الإطلاقات. هذا ليس عيباً؛ في بيئة تخرج فيها نسبة ضئيلة من الإصدارات وتكون فيها بيانات PnL والهوية واللقطات متضاربة، فإن قابلية الشرح وقابلية الخروج أهم من سرعة الدخول أو headline win.

**هذه خوارزمية بحث وPaper Trading، وليست نصيحة استثمارية ولا ضمان ربح.**

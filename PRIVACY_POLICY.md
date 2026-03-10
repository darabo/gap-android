# Gap Mesh Privacy Policy

_Last updated: March 2026_

## Our Commitment

Gap Mesh is designed with privacy as its foundation. We believe private communication is a fundamental human right. This policy explains how Gap Mesh protects your privacy.

## Summary

- **No personal data collection** - We don't collect names, emails, location, metadata, or phone numbers
- **Hybrid Functionality** - Gap Mesh offers two modes of communication:
  - **Bluetooth Mesh Chat**: This mode is completely offline, using peer-to-peer Bluetooth connections. It does not use any servers or internet connection.
  - **Geohash Chat**: This mode uses an internet connection to communicate with others in a specific geographic area. It relies on Nostr relays for message transport. Connections to these relays are routed through the Tor network (via Arti) to anonymize your connection metadata and protect your privacy.
- **No tracking** - We have no analytics, telemetry, or user tracking
- **Open source** - You can verify these claims by reading our code

## What Information Gap Mesh Stores

### On Your Device Only

1. **Identity Key**
   - A cryptographic key generated on first launch
   - Stored locally in your device's secure storage (StrongBox on Android / Secure Enclave on iOS)
   - Allows you to maintain "favorite" relationships across app restarts
   - Never leaves your device

2. **Nickname**
   - The display name you choose (or auto-generated)
   - Stored only on your device
   - Shared with peers you communicate with

3. **Message History** (if enabled)
   - When room owners enable retention, messages are saved locally
   - Stored encrypted on your device
   - You can delete this at any time

4. **Favorite Peers**
   - Public keys of peers you mark as favorites
   - Stored only on your device
   - Allows you to recognize these peers in future sessions

### Temporary Session Data

During each session, Gap Mesh temporarily maintains:

- Active peer connections (forgotten when app closes)
- Routing information for message delivery
- Cached messages for offline peers (12 hours max)

## What Information is Shared

### With Other Gap Mesh Users

When you use Gap Mesh, nearby peers can see:

- Your chosen nickname
- Your ephemeral public key (changes each session)
- Messages you send to public rooms or directly to them
- Your approximate Bluetooth signal strength (for connection quality)

### With Room Members

When you join a password-protected room:

- Your messages are visible to others with the password
- Your nickname appears in the member list
- Room owners can see you've joined

## What We DON'T Do

Gap Mesh **never**:

- Collects personal information
- Tracks your location
- Stores data on servers
- Shares data with third parties
- Uses analytics or telemetry
- Creates user profiles
- Requires registration

## Encryption

All private messages use end-to-end encryption:

- **X25519** for key exchange
- **AES-256-GCM** for message encryption
- **Ed25519** for digital signatures
- **Argon2id** for password-protected rooms

## Your Rights

You have complete control:

- **Delete Everything (Panic Wipe)**: Triple-tap the logo to instantly wipe all data. This triggers a crash-resilient secure wipe that permanently erases all cryptographic keys and data from secure hardware storage (StrongBox/Secure Enclave).
- **Leave Anytime**: Close the app and your presence disappears
- **No Account**: Nothing to delete from servers because there are none
- **Portability**: Your data never leaves your device unless you export it

## Permissions We Request

Gap Mesh requests several Android permissions to provide its features. Here's exactly what each permission is used for:

### Bluetooth Permissions
- **Purpose**: Required for peer-to-peer offline mesh networking
- **What we access**: Nearby Bluetooth devices running Gap Mesh
- **What we DON'T do**: Track your location, collect device identifiers, or share this data with anyone
- **Can you revoke it?**: Yes, but the offline mesh features won't work

### Location Permissions
- **Purpose**: Required by Android for Bluetooth scanning, and to enable geohash location-based chat rooms
- **What we access**: Your approximate location (only when using geohash features)
- **What we DON'T do**: Send your location to servers, track your movements, or create location histories
- **Background location**: Only used to maintain mesh connections when the app is closed
- **Can you revoke it?**: Yes, but Bluetooth discovery and geohash features won't work

### Internet & Network State
- **Purpose**: Connect to Nostr relays for geohash chat (routed through Tor for privacy)
- **What we access**: Internet connection status
- **What we DON'T do**: Track your browsing, collect IP addresses (Tor hides them), or monitor your activity
- **Can you revoke it?**: No (system permission), but geohash features simply won't work offline

### Notifications
- **Purpose**: Alert you when you receive messages
- **What we access**: Ability to show notifications
- **What we DON'T do**: Send marketing notifications or track notification interactions
- **Can you revoke it?**: Yes, but you won't receive message alerts

### Camera
- **Purpose**: Scan QR codes for security verification
- **What we access**: Camera only when you explicitly use the QR scanner
- **What we DON'T do**: Take photos without permission, record video, or access your photo library
- **Can you revoke it?**: Yes, but QR verification won't work

### Microphone
- **Purpose**: Record voice notes to send in chats
- **What we access**: Microphone only when you press and hold the voice note button
- **What we DON'T do**: Record in the background, store unencrypted audio, or listen without your explicit action
- **Can you revoke it?**: Yes, but voice note features won't work

### Storage/Media Access
- **Purpose**: Share photos, videos, and files in chats; detect screenshots for privacy warnings
- **What we access**: Only media files you explicitly select to share
- **What we DON'T do**: Scan your files, upload without permission, or access files you don't select
- **Can you revoke it?**: Yes, but you can't share media files

### WiFi Aware (Optional)
- **Purpose**: Enable high-bandwidth mesh networking over WiFi when available
- **What we access**: WiFi Aware capability (not your WiFi networks or passwords)
- **What we DON'T do**: Connect to WiFi networks, access WiFi passwords, or track WiFi locations
- **Can you revoke it?**: Yes, the app works fine without it (falls back to Bluetooth)

### Battery Optimization Exception
- **Purpose**: Keep the mesh service running reliably in the background
- **What we access**: Ability to run without aggressive battery restrictions
- **What we DON'T do**: Drain battery unnecessarily (service is optimized for efficiency)
- **Can you revoke it?**: Yes, but background connectivity may be unreliable

### Foreground Service
- **Purpose**: Maintain mesh connections and sync messages while you use other apps
- **What we access**: Ability to run a persistent background service
- **What we DON'T do**: Run hidden services or use this for tracking
- **Can you revoke it?**: No (system permission), but you can stop the service by closing the app

**Important Privacy Note**: Location permission is technically required by Android for Bluetooth LE scanning (a system requirement we cannot bypass), but Gap Mesh never accesses, stores, or transmits your actual location data unless you explicitly use the geohash location-based chat feature. When you do use geohash features, your location is processed locally and never sent to servers—only the geohash identifier (a general area code) is used.

## Children's Privacy

Gap Mesh does not knowingly collect information from children. The app has no age verification because it collects no personal information from anyone.

## Data Retention

- **Messages**: Deleted from memory when app closes (unless room retention is enabled)
- **Identity Key**: Persists until you delete the app
- **Favorites**: Persist until you remove them or delete the app
- **Everything Else**: Exists only during active sessions

## Security Measures

- All communication is encrypted
- No data transmitted to servers (there are none)
- Open source code for public audit
- Regular security updates
- Cryptographic signatures prevent tampering
- Strong hardware-backed storage encryption
- Emergency panic wipe system designed to survive app crashes

## Changes to This Policy

If we update this policy:

- The "Last updated" date will change
- The updated policy will be included in the app
- No retroactive changes can affect data (since we don't collect any)

## Contact

Gap Mesh is an open source project. For privacy questions:

- View our source code:
  - Android: https://github.com/darabo/gap-android-main
  - iOS: https://github.com/darabo/gapmesh-ios/tree/main
- Open an issue on GitHub
- Join the discussion in public rooms

## Philosophy

Privacy isn't just a feature—it's the entire point. Gap Mesh proves that modern communication doesn't require surrendering your privacy. No accounts, no servers, no surveillance. Just people talking freely.

---

_This policy is released into the public domain under the MIT License, just like Gap Mesh itself._

---

# سیاست حفظ حریم خصوصی Gap Mesh

_آخرین به روز رسانی: مارس 2026_

## تعهد ما

برنامه Gap Mesh با توجه به حفظ حریم خصوصی طراحی شده است. ما معتقدیم ارتباط خصوصی یک حق اساسی بشر است. این سیاست نحوه حفاظت Gap Mesh از حریم خصوصی شما را توضیح می‌دهد.

## خلاصه

- **بدون جمع‌آوری داده‌های شخصی** - ما نام، ایمیل، یا شماره تلفن‌ها را جمع‌آوری نمی‌کنیم.
- **عملکرد ترکیبی** - برنامه Gap Mesh دو حالت ارتباطی ارائه می‌دهد:
  - **چت شبکه بلوتوث (Mesh)**: این حالت کاملاً آفلاین است و از اتصالات نظیر به نظیر بلوتوث استفاده می‌کند. از هیچ سرور یا اتصال اینترنتی استفاده نمی‌کند.
  - **چت Geohash**: این حالت از اتصال اینترنت برای ارتباط با دیگران در یک منطقه جغرافیایی خاص استفاده می‌کند و به رله‌های Nostr متکی است. اتصالات به این رله‌ها از طریق شبکه Tor (توسط Arti) هدایت می‌شوند تا فراداده‌های اتصال شما ناشناس بمانند و حریم خصوصی‌تان حفظ شود.
- **بدون ردیابی** - ما هیچ سیستم تحلیلی، تله‌متری یا ردیابی کاربر نداریم.
- **متن‌باز** - شما می‌توانید این ادعاها را با خواندن کدهای ما تأیید کنید.

## آنچه Gap Mesh ذخیره می‌کند

### فقط در دستگاه شما

۱. **کلید هویت (Identity Key)**

- یک کلید رمزنگاری که در اولین راه‌اندازی تولید می‌شود.
- به صورت محلی در فضای ذخیره‌سازی امن دستگاه شما (StrongBox در اتدروید / Secure Enclave در iOS) نگهداری می‌شود.
- به شما امکان می‌دهد روابط "مورد علاقه" را بین اجراهای مختلف برنامه حفظ کنید.
- هرگز از دستگاه شما خارج نمی‌شود.

۲. **نام مستعار (Nickname)**

- نام نمایشی که انتخاب می‌کنید (یا به طور خودکار تولید می‌شود).
- فقط روی دستگاه شما ذخیره می‌شود.
- با همتایانی که با آنها ارتباط برقرار می‌کنید به اشتراک گذاشته می‌شود.

۳. **تاریخچه پیام‌ها** (در صورت فعال بودن)

- وقتی مالکان اتاق‌ها نگهداری پیام‌ها را فعال کنند، پیام‌ها به صورت محلی ذخیره می‌شوند.
- به صورت رمزگذاری شده روی دستگاه شما ذخیره می‌شوند.
- می‌توانید در هر زمان آنها را حذف کنید.

۴. **همتایان مورد علاقه (Favorite Peers)**

- کلیدهای عمومی همتایانی که به عنوان مورد علاقه نشانه‌گذاری می‌کنید.
- فقط روی دستگاه شما ذخیره می‌شود.
- به شما اجازه می‌دهد این همتایان را در جلسات آینده بشناسید.

### داده‌های موقت جلسه (Session)

در طول هر جلسه، Gap Mesh به طور موقت این موارد را نگهداری می‌کند:

- اتصالات فعال همتایان (با بسته شدن برنامه فراموش می‌شوند)
- اطلاعات مسیریابی برای تحویل پیام
- پیام‌های کش شده برای همتایان آفلاین (حداکثر ۱۲ ساعت)

## چه اطلاعاتی به اشتراک گذاشته می‌شود

### با سایر کاربران Gap Mesh

هنگامی که از Gap Mesh استفاده می‌کنید، همتایان نزدیک می‌توانند موارد زیر را ببینند:

- نام مستعار انتخابی شما
- کلید عمومی موقت شما (در هر جلسه تغییر می‌کند)
- پیام‌هایی که به اتاق‌های عمومی یا مستقیماً به آنها ارسال می‌کنید
- قدرت تقریبی سیگنال بلوتوث شما (برای کیفیت اتصال)

### با اعضای اتاق

هنگامی که به یک اتاق محافظت شده با رمز عبور می‌پیوندید:

- پیام‌های شما برای دیگرانی که رمز عبور را دارند قابل مشاهده است.
- نام مستعار شما در لیست اعضا ظاهر می‌شود.
- مالکان اتاق می‌توانند پیوستن شما را ببینند.

## کارهایی که ما انجام نمی‌دهیم

برنامه Gap Mesh **هرگز**:

- اطلاعات شخصی را جمع‌آوری نمی‌کند.
- مکان شما را ردیابی نمی‌کند.
- داده‌ای را روی سرورها ذخیره نمی‌کند.
- داده‌ها را با اشخاص ثالث به اشتراک نمی‌گذارد.
- از سیستم‌های تحلیلی یا تله‌متری استفاده نمی‌کند.
- پروفایل کاربری ایجاد نمی‌کند.
- نیازی به ثبت‌نام ندارد.

## رمزگذاری

تمامی پیام‌های خصوصی از رمزگذاری سرتاسری (End-to-End) استفاده می‌کنند:

- **X25519** برای تبادل کلید
- **AES-256-GCM** برای رمزگذاری پیام
- **Ed25519** برای امضاهای دیجیتال
- **Argon2id** برای اتاق‌های محافظت شده با رمز عبور

## حقوق شما

شما کنترل کامل دارید:

- **حذف همه چیز (شیر برقی / Panic Wipe)**: سه بار ضربه روی لوگو تمام داده‌ها را به سرعت پاک می‌کند. این کار یک پاکسازی امن و مقاوم در برابر خرابی را فعال می‌کند که به طور دائم تمام کلیدهای رمزنگاری و داده‌ها را از فضای ذخیره‌سازی سخت‌افزاری امن پاک می‌کند.
- **ترک کردن در هر زمان**: برنامه را ببندید تا حضور شما ناپدید شود.
- **بدون حساب کاربری**: چیزی برای پاک کردن از سرورها وجود ندارد چون اصلاً سروری وجود ندارد.
- **قابلیت انتقال**: داده‌های شما هرگز از دستگاهتان خارج نمی‌شوند مگر اینکه آنها را خروجی (Export) بگیرید.

## مجوزهایی که درخواست می‌کنیم

برنامه Gap Mesh چندین مجوز اندروید را برای ارائه امکانات خود درخواست می‌کند. در اینجا دقیقاً توضیح می‌دهیم که هر مجوز برای چه استفاده می‌شود:

### مجوزهای بلوتوث
- **هدف**: برای شبکه مش آفلاین نظیر به نظیر لازم است
- **چه چیزی دسترسی داریم**: دستگاه‌های بلوتوث نزدیک که Gap Mesh را اجرا می‌کنند
- **چه کاری انجام نمی‌دهیم**: مکان شما را ردیابی نمی‌کنیم، شناسه دستگاه جمع‌آوری نمی‌کنیم، یا این داده‌ها را با کسی به اشتراک نمی‌گذاریم
- **آیا می‌توانید آن را لغو کنید؟**: بله، اما امکانات مش آفلاین کار نمی‌کنند

### مجوزهای مکان
- **هدف**: برای اسکن بلوتوث توسط اندروید لازم است، و برای فعال کردن اتاق‌های چت مبتنی بر مکان geohash
- **چه چیزی دسترسی داریم**: مکان تقریبی شما (فقط هنگام استفاده از امکانات geohash)
- **چه کاری انجام نمی‌دهیم**: مکان شما را به سرورها ارسال نمی‌کنیم، حرکات شما را ردیابی نمی‌کنیم، یا تاریخچه مکان ایجاد نمی‌کنیم
- **مکان در پس‌زمینه**: فقط برای حفظ اتصالات مش هنگام بسته شدن برنامه استفاده می‌شود
- **آیا می‌توانید آن را لغو کنید؟**: بله، اما شناسایی بلوتوث و امکانات geohash کار نمی‌کنند

### اینترنت و وضعیت شبکه
- **هدف**: اتصال به رله‌های Nostr برای چت geohash (از طریق Tor برای حریم خصوصی)
- **چه چیزی دسترسی داریم**: وضعیت اتصال اینترنت
- **چه کاری انجام نمی‌دهیم**: مرور شما را ردیابی نمی‌کنیم، آدرس‌های IP جمع‌آوری نمی‌کنیم (Tor آنها را پنهان می‌کند)، یا فعالیت شما را رصد نمی‌کنیم
- **آیا می‌توانید آن را لغو کنید؟**: خیر (مجوز سیستمی)، اما امکانات geohash در حالت آفلاین کار نمی‌کنند

### اعلان‌ها
- **هدف**: هنگام دریافت پیام به شما هشدار می‌دهد
- **چه چیزی دسترسی داریم**: توانایی نمایش اعلان‌ها
- **چه کاری انجام نمی‌دهیم**: اعلان‌های بازاریابی ارسال نمی‌کنیم یا تعاملات اعلان را ردیابی نمی‌کنیم
- **آیا می‌توانید آن را لغو کنید؟**: بله، اما هشدارهای پیام دریافت نمی‌کنید

### دوربین
- **هدف**: اسکن کدهای QR برای تأیید امنیتی
- **چه چیزی دسترسی داریم**: دوربین فقط زمانی که شما صریحاً از اسکنر QR استفاده می‌کنید
- **چه کاری انجام نمی‌دهیم**: بدون اجازه عکس نمی‌گیریم، ویدیو ضبط نمی‌کنیم، یا به کتابخانه عکس شما دسترسی نداریم
- **آیا می‌توانید آن را لغو کنید؟**: بله، اما تأیید QR کار نمی‌کند

### میکروفون
- **هدف**: ضبط پیام‌های صوتی برای ارسال در چت‌ها
- **چه چیزی دسترسی داریم**: میکروفون فقط زمانی که دکمه پیام صوتی را نگه می‌دارید
- **چه کاری انجام نمی‌دهیم**: در پس‌زمینه ضبط نمی‌کنیم، صدای رمزگذاری نشده ذخیره نمی‌کنیم، یا بدون اقدام صریح شما گوش نمی‌دهیم
- **آیا می‌توانید آن را لغو کنید؟**: بله، اما امکانات پیام صوتی کار نمی‌کنند

### دسترسی به فضای ذخیره‌سازی/رسانه
- **هدف**: به اشتراک‌گذاری عکس‌ها، ویدیوها و فایل‌ها در چت‌ها؛ تشخیص اسکرین‌شات برای هشدارهای حریم خصوصی
- **چه چیزی دسترسی داریم**: فقط فایل‌های رسانه‌ای که صریحاً برای اشتراک‌گذاری انتخاب می‌کنید
- **چه کاری انجام نمی‌دهیم**: فایل‌های شما را اسکن نمی‌کنیم، بدون اجازه آپلود نمی‌کنیم، یا به فایل‌هایی که انتخاب نمی‌کنید دسترسی نداریم
- **آیا می‌توانید آن را لغو کنید؟**: بله، اما نمی‌توانید فایل‌های رسانه‌ای را به اشتراک بگذارید

### WiFi Aware (اختیاری)
- **هدف**: فعال کردن شبکه مش با پهنای باند بالا از طریق WiFi در صورت وجود
- **چه چیزی دسترسی داریم**: قابلیت WiFi Aware (نه شبکه‌های WiFi یا رمزهای عبور شما)
- **چه کاری انجام نمی‌دهیم**: به شبکه‌های WiFi متصل نمی‌شویم، رمزهای عبور WiFi را دسترسی نداریم، یا مکان‌های WiFi را ردیابی نمی‌کنیم
- **آیا می‌توانید آن را لغو کنید؟**: بله، برنامه بدون آن به خوبی کار می‌کند (به بلوتوث بازمی‌گردد)

### استثنای بهینه‌سازی باتری
- **هدف**: سرویس مش را به طور قابل اعتماد در پس‌زمینه نگه می‌دارد
- **چه چیزی دسترسی داریم**: توانایی اجرا بدون محدودیت‌های تهاجمی باتری
- **چه کاری انجام نمی‌دهیم**: باتری را بی‌دلیل تخلیه نمی‌کنیم (سرویس برای کارایی بهینه شده است)
- **آیا می‌توانید آن را لغو کنید؟**: بله، اما اتصال در پس‌زمینه ممکن است غیرقابل اعتماد باشد

### سرویس پیش‌زمینه
- **هدف**: حفظ اتصالات مش و همگام‌سازی پیام‌ها در حین استفاده از برنامه‌های دیگر
- **چه چیزی دسترسی داریم**: توانایی اجرای یک سرویس پس‌زمینه پایدار
- **چه کاری انجام نمی‌دهیم**: سرویس‌های پنهان اجرا نمی‌کنیم یا از این برای ردیابی استفاده نمی‌کنیم
- **آیا می‌توانید آن را لغو کنید؟**: خیر (مجوز سیستمی)، اما می‌توانید سرویس را با بستن برنامه متوقف کنید

**نکته مهم حریم خصوصی**: مجوز مکان از نظر فنی توسط اندروید برای اسکن Bluetooth LE لازم است (یک نیاز سیستمی که نمی‌توانیم از آن عبور کنیم)، اما Gap Mesh هرگز داده‌های واقعی مکان شما را دسترسی، ذخیره یا منتقل نمی‌کند مگر اینکه صریحاً از امکان چت مبتنی بر مکان geohash استفاده کنید. هنگامی که از امکانات geohash استفاده می‌کنید، مکان شما به صورت محلی پردازش می‌شود و هرگز به سرورها ارسال نمی‌شود—فقط شناسه geohash (یک کد منطقه عمومی) استفاده می‌شود.

## حریم خصوصی کودکان

برنامه Gap Mesh به صورت آگاهانه هیچ اطلاعاتی از کودکان جمع‌آوری نمی‌کند. این برنامه به تأیید سن نیازی ندارد زیرا اطلاعات شخصی هیچ‌کس را جمع‌آوری نمی‌کند.

## نگهداری داده‌ها

- **پیام‌ها**: با بسته شدن برنامه از حافظه حذف می‌شوند (مگر اینکه نگهداری در اتاق فعال باشد)
- **کلید هویت**: تا زمانی که برنامه را حذف کنید باقی می‌ماند.
- **مورد علاقه‌ها**: تا زمانی که آنها را حذف کنید یا برنامه را پاک کنید باقی می‌مانند.
- **بقیه موارد**: فقط در طول جلسات فعال وجود دارند.

## اقدامات امنیتی

- تمام ارتباطات رمزگذاری شده‌اند.
- هیچ داده‌ای به سرورها منتقل نمی‌شود (سروری وجود ندارد).
- کد متن‌باز برای بررسی عمومی.
- به‌روزرسانی‌های امنیتی منظم.
- امضاهای رمزنگاری از دستکاری جلوگیری می‌کنند.
- رمزگذاری قدرتمند فضای ذخیره‌سازی با پشتیبانی سخت‌افزاری.
- سیستم پاکسازی اضطراری (Panic Wipe) طراحی شده برای تاب‌آوری در برابر خرابی برنامه.

## تغییرات در این سیاست

اگر این سیاست را تغییر دهیم:

- تاریخ "آخرین به‌روزرسانی" تغییر خواهد کرد.
- سیاست به‌روز شده در برنامه گنجانده می‌شود.
- هیچ تغییری نمی‌تواند روی داده‌های قبلی اثر بگذارد (زیرا ما داده‌ای جمع‌آوری نمی‌کنیم).

## تماس با ما

برنامه Gap Mesh یک پروژه متن‌باز است. برای سؤالات مربوط به حریم خصوصی:

- کدهای متن‌باز ما را بررسی کنید:
  - اندروید: https://github.com/darabo/gap-android-main
  - آی‌اواس: https://github.com/darabo/gapmesh-ios/tree/main
- در گیت‌هاب (GitHub) ایشیو (Issue) باز کنید.
- به بحث‌ها در اتاق‌های عمومی بپیوندید.

## فلسفه ما

حریم خصوصی فقط یک قابلیت نیست—همه چیزِ این برنامه است. Gap Mesh ثابت می‌کند که ارتباطات مدرن نیازی به تسلیم حریم خصوصی شما ندارد. بدون حساب کاربری، بدون سرور، بدون نظارت. فقط انسان‌هایی که آزادانه صحبت می‌کنند.

---

_این سیاست همانند خود Gap Mesh، تحت لیسانس MIT License در مالکیت عمومی منتشر شده است._

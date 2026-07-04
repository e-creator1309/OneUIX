# Session Notes — Reverse Engineering Samsung APKs
## دليل الجلسات — للـ AI اللي بيجي بعدك

> هذا الملف يوثّق العملية والأدوات والنتائج حتى لا تُعاد من الصفر في كل جلسة

---

## ⚡ تنزيل الـ APK بدون حجب (الحل الصح)

### المشكلة
- **APKMirror** → محجوب بـ Cloudflare على الـ server ✗
- **APKPure browser** → Cloudflare أيضاً ✗

### الحل — APKPure Direct Download + Mobile User-Agent

```bash
mkdir -p /tmp/apk_re && cd /tmp/apk_re

curl -L \
  -A "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" \
  -H "Accept: application/octet-stream,*/*" \
  -H "Referer: https://apkpure.com/" \
  "https://d.apkpure.com/b/APK/com.sec.android.app.launcher?version=latest" \
  -o target.apk \
  --max-time 120

# تحقق:
file target.apk
# → Java archive data (JAR)  = ✅ ناجح
# → HTML document             = ✗ مبلوك، جرب user-agent تاني
```

### لإصدار محدد:
```bash
# غيّر version=latest بـ versionCode=XXXXXXXXX
"https://d.apkpure.com/b/APK/PACKAGE_NAME?versionCode=1600705003"
```

---

## 🏆 أفضل أداة لتحليل الـ APK على السيرفر — Python DEX Parser

### ليش مش JADX؟
| الأداة | السرعة | المشكلة |
|--------|--------|---------|
| JADX | 5-15 دقيقة | JVM ثقيل، timeout على Replit |
| apktool | دقيقة | smali صعب القراءة |
| **Python DEX Parser** | **ثوانٍ** | **بدون أي library، مباشر على binary** |

### السبب التقني
DEX بيخزّن النصوص بـ **length-prefix** مش null-terminated، لذا `strings` العادي ما بيشتغل صح.
الحل: نقرأ الـ DEX string table مباشرة من الـ header binary.

### الأداة الكاملة (zero dependencies):

```python
#!/usr/bin/env python3
"""Fast DEX string/class scanner — no pip needed"""
import struct, re

def read_uleb128(data, pos):
    result, shift = 0, 0
    while True:
        b = data[pos]; pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80): break
        shift += 7
    return result, pos

def parse_dex(path):
    with open(path, 'rb') as f:
        data = f.read()
    assert data[:4] == b'dex\n', "Not a DEX file"
    string_ids_size = struct.unpack_from('<I', data, 0x38)[0]
    string_ids_off  = struct.unpack_from('<I', data, 0x3C)[0]
    type_ids_size   = struct.unpack_from('<I', data, 0x40)[0]
    type_ids_off    = struct.unpack_from('<I', data, 0x44)[0]
    strings = []
    for i in range(string_ids_size):
        off = struct.unpack_from('<I', data, string_ids_off + i * 4)[0]
        length, pos = read_uleb128(data, off)
        try: strings.append(data[pos:pos+length].decode('utf-8', errors='ignore'))
        except: strings.append('')
    classes = []
    for i in range(type_ids_size):
        sid = struct.unpack_from('<I', data, type_ids_off + i * 4)[0]
        classes.append(strings[sid])
    return strings, classes

def scan_apk(apk_path):
    import zipfile, os, tempfile
    all_strings, all_classes = [], []
    with zipfile.ZipFile(apk_path) as z:
        dex_files = [n for n in z.namelist() if re.match(r'classes\d*\.dex', n)]
        with tempfile.TemporaryDirectory() as tmp:
            for dex in dex_files:
                z.extract(dex, tmp)
                s, c = parse_dex(os.path.join(tmp, dex))
                all_strings += s; all_classes += c
    ALL = sorted(set(all_strings))
    CLS = sorted(set(all_classes))
    return ALL, CLS

# استخدام:
ALL, CLS = scan_apk('/tmp/apk_re/target.apk')

# البحث عن Samsung feature flags
print("=== CoreRune / Feature Flags ===")
for s in ALL:
    if re.match(r'RUNE_|SBR_|FEATURE_|CscFeature', s): print(" ", s)

print("\n=== Pref Keys ===")
for s in ALL:
    if s.startswith('pref_'): print(" ", s)

print("\n=== Settings Keys ===")
for s in ALL:
    if re.match(r'(TASKBAR|DEX_MODE|MULTI_WINDOW)', s): print(" ", s)
```

---

## 🛠️ تجهيز Java على Replit (NixOS)

```bash
# Replit = NixOS → مش apt، استخدم nix
nix-env -iA nixpkgs.jdk

# Java مش في PATH مباشرة — استخدم المسار الكامل:
~/.nix-profile/bin/java -version

# JADX إذا احتجته (للكلاسات المبهمة):
curl -L -o jadx.zip "https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0.zip"
unzip -q jadx.zip -d jadx_tool
~/.nix-profile/bin/java -jar jadx_tool/lib/jadx-1.5.0-all.jar \
  --output-dir ./decompiled --threads-count 2 --no-res target.apk
```

---

## 🔍 ما نبحث عنه في الـ Samsung Launcher

### Feature Gates الأهم
| النوع | Pattern | الاستخدام |
|-------|---------|-----------|
| **CoreRune flags** | `RUNE_*`, `SBR_*` | Samsung feature toggle system |
| **CscFeature keys** | `CscFeature_Launcher_*` | Region/CSC locked features |
| **SemFloatingFeature** | `FloatingFeature_*` | Hardware feature flags |
| **Pref keys** | `pref_*` | SharedPreferences settings |
| **Settings.System** | `TASKBAR_ON_OFF`, `DEX_MODE` | System-level settings |

### البحث الذكي عن الكلاسات المبهمة
```bash
# لما الكلاسات مبهمة (a, b, c...) ابحث بالـ string literals:
grep -r "TASKBAR_ON_OFF\|taskbar_available" ./decompiled/sources/
grep -r "isTablet\|isLargeScreen\|isFoldable"  ./decompiled/sources/

# بعدين افتح الكلاس اللي طلع واتبع الـ call chain
```

---

## 📦 المشروع الحالي — com.sec.android.app.launcher v1600705003

### الميزات المخبّأة اللي وجدناها

| الميزة | الـ Key في الكود | السبب | أولوية الـ Hook |
|--------|----------------|-------|----------------|
| **Taskbar** | `TASKBAR_ON_OFF`, `taskbar_available` | `isTablet` check | 🔴 صعب — محتاج نلاقي الـ check |
| **Landscape mode** | `pref_support_landscape_mode` | tablet-only | 🟡 متوسط |
| **Multi-window tray** | `MULTI_WINDOW_TRAY` | Fold/tablet | 🔴 صعب |
| **Media Page** | `pref_media_page_enabled` | pref مخفي | 🟢 سهل |
| **Discover/Bixby page** | `pref_discover_enabled` | pref مخفي | 🟢 سهل |
| **Recommended apps (Recents)** | `pref_overview_recommended_apps` | pref مخفي | 🟢 سهل |
| **WonderLand Wallpaper** | `isWonderLandAmbientWallpaperEnabled` | method hook | 🟢 سهل |
| **Zero Page** | `CscFeature_Launcher_SupportZeroPage` | CSC locked | 🟡 متوسط |

### الخطوة القادمة للـ Taskbar
لازم نعرف أين بالضبط بيتحقق من `isTablet`:
```bash
# بعد JADX decompile:
grep -r "taskbar_available\|TASKBAR_ON_OFF" ./decompiled/sources/ -l
# افتح الكلاسات واتبع اللي بيعمل isTablet() أو getLargeScreenSize()
```

---

## هيكل Hook جديد في OneUIX

```kotlin
// 1. ملف جديد: hook/Launcher.kt
object LauncherFeature {
    fun enableMediaPage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != "com.sec.android.app.launcher") return
        // hook SharedPreferences أو الـ method اللي بتقرأ pref_media_page_enabled
    }
}

// 2. Preference.kt — أضف:
@Serializable
data class Launcher(
    val enableMediaPage: Boolean = false,
    val enableDiscoverPage: Boolean = false,
    val enableLandscapeMode: Boolean = false,
    val enableOverviewRecommendedApps: Boolean = false,
    val enableWonderLandWallpaper: Boolean = false,
)

// 3. Main.kt — أضف في when block:
Package.LAUNCHER -> {
    if (preference.launcher.enableMediaPage)
        LauncherFeature.enableMediaPage(lpparam)
}

// 4. strings.xml + DetailPane*.kt للـ UI
```

---

## نصايح للـ AI اللي بيجي

1. **ابدأ بالـ Python DEX Parser** — أسرع من JADX بـ 100x للبحث عن strings
2. **JADX فقط للكلاسات المبهمة** — لما تحتاج تفهم الـ logic وليس بس الأسماء
3. **APKPure + mobile user-agent** — الطريقة الوحيدة المضمونة على الـ server
4. **nohup ما بيشتغل على Replit** — شغّل JADX مباشرة مع timeout طويل
5. **JADX jar اسمه `jadx-1.5.0-all.jar`** مش `jadx.jar`
6. **DexKit** اللي بيستخدمه OneUIX هو أفضل حل للبحث في runtime — استخدمه للـ hooks المعقدة

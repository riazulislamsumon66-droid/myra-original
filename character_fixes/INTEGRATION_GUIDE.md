# MAYA App — Fix Summary & Integration Guide

## ❌ Missing Files Found (Build Breaks)

### 1. WaveformView.kt — MISSING
**Path:** `app/src/main/java/com/maya/assistant/ui/main/WaveformView.kt`
**Used in:** CallAssistantActivity.kt line ~35
**Fix:** Added WaveformView.kt (bar-style audio visualizer)

### 2. overlay_orb.xml — EXISTS ✅ (false alarm)
Actually found in zip. No action needed.

---

## ⚠️ Call Monitoring — Issues Found

### Issue 1: Android 10+ phone number null
**Problem:** `EXTRA_INCOMING_NUMBER` returns null on Android 10+ without READ_CALL_LOG runtime permission.

**Fix in CallReceiver.kt:**
```kotlin
// CallReceiver.kt — onReceive() এ এই change করো:
override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
    val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

    // Android 10+ এ number BroadcastReceiver থেকে পাওয়া যায় না
    // CallMonitorService এর TelephonyCallback থেকে পাওয়া ভালো
    when (stateStr) {
        TelephonyManager.EXTRA_STATE_RINGING -> {
            val serviceIntent = Intent(context, CallMonitorService::class.java).apply {
                action = "START_MONITORING"
            }
            try {
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e("CALL_RCVR", "Service start failed: ${e.message}")
            }
        }
    }
}
```

### Issue 2: Duplicate phone state listening
**Problem:** CallReceiver AND CallMonitorService দুটোই phone state শুনছে — এটা wasteful এবং double-trigger হতে পারে।

**Fix:** CallReceiver শুধু CallMonitorService start করবে। Number resolve করবে শুধু CallMonitorService।

### Issue 3: FOREGROUND_SERVICE_TYPE missing on Android 14+
**Manifest এ এটা নিশ্চিত করো:**
```xml
<service
    android:name=".service.CallMonitorService"
    android:exported="false"
    android:foregroundServiceType="phoneCall"/>
```
✅ Already correct in your manifest.

### Issue 4: TelephonyCallback এ phone number পাওয়া যায় না (Android 12+)
**Problem:** `TelephonyCallback.CallStateListener.onCallStateChanged(state)` এ number নেই।

**Fix in CallMonitorService.kt — handleCallState():**
```kotlin
// Android 12+ এ number পেতে READ_CALL_LOG + query করো:
private fun getLastIncomingNumber(): String? {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
        != PackageManager.PERMISSION_GRANTED) return null
    
    val cursor = contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE),
        "${CallLog.Calls.TYPE} = ${CallLog.Calls.INCOMING_TYPE}",
        null,
        "${CallLog.Calls.DATE} DESC"
    )
    return cursor?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}
```

---

## 🎮 3D Character — Integration Steps

### Step 1: Files রাখো
```
app/src/main/java/com/maya/assistant/
    ui/character/
        CharacterOverlayView.kt     ← NEW
    service/
        MayaCharacterService.kt     ← NEW
    ui/main/
        WaveformView.kt             ← NEW (was missing)

app/src/main/res/layout/
    overlay_character_3d.xml        ← NEW
```

### Step 2: AndroidManifest.xml এ service add করো
```xml
<!-- After existing MayaOverlayService -->
<service
    android:name=".service.MayaCharacterService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="character_overlay"/>
</service>
```

### Step 3: MainActivity থেকে character start করো
```kotlin
// onCreate() এ:
private fun startCharacterOverlay() {
    if (!Settings.canDrawOverlays(this)) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
        return
    }
    val intent = Intent(this, MayaCharacterService::class.java).apply {
        action = MayaCharacterService.ACTION_SHOW
    }
    startForegroundService(intent)
}
```

### Step 4: AI state change হলে character update করো
```kotlin
// AI কথা বলতে শুরু করলে:
fun notifyCharacterTalking() {
    Intent(this, MayaCharacterService::class.java).apply {
        action = MayaCharacterService.ACTION_SET_STATE
        putExtra(MayaCharacterService.EXTRA_STATE, "TALKING")
    }.also { startService(it) }
}

// User কথা বললে:
fun notifyCharacterListening() {
    Intent(this, MayaCharacterService::class.java).apply {
        action = MayaCharacterService.ACTION_SET_STATE
        putExtra(MayaCharacterService.EXTRA_STATE, "LISTENING")
    }.also { startService(it) }
}

// Mode change:
fun setCharacterMode(mode: String) { // "GF", "PROFESSIONAL", "FRIEND"
    Intent(this, MayaCharacterService::class.java).apply {
        action = MayaCharacterService.ACTION_SET_MODE
        putExtra(MayaCharacterService.EXTRA_MODE, mode)
    }.also { startService(it) }
}
```

### Step 5: Settings থেকে mode connect করো
```kotlin
// SettingsActivity এ personality_mode save হলে:
val mode = when (selectedPersonality) {
    "gf" -> "GF"
    "professional" -> "PROFESSIONAL"
    "friend" -> "FRIEND"
    else -> "DEFAULT"
}
setCharacterMode(mode)
```

---

## 🏆 3D Upgrade Path (Optional — Real 3D Model)

এখন character Custom Canvas দিয়ে drawn। সত্যিকারের 3D GLB model চাইলে:

**build.gradle এ add করো:**
```gradle
implementation("io.github.sceneview:sceneview:2.2.1")
```

**Free 3D avatar নামাও:**
- https://readyplayer.me (free custom avatar, exports GLB)
- https://sketchfab.com (search "anime girl" — CC license)
- Mixamo.com (free characters + animations, export GLB)

**CharacterOverlayView এর পরিবর্তে SceneView use করবে:**
```xml
<io.github.sceneview.SceneView
    android:id="@+id/sceneView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:sceneBackgroundColor="@android:color/transparent"/>
```

---

## ✅ Character Behavior by Mode

| Mode | Hair | Outfit | Special | Behavior |
|------|------|--------|---------|----------|
| GF | Dark + bow | Sky blue | Heart blush, jumps a lot | Excited, frequent happy state |
| Professional | Dark brown | Navy suit | Glasses | Calm, minimal gestures |
| Friend | Warm brown | Pink casual | Smile badge | Relaxed, frequent smile |
| Default | Dark | Purple | None | Standard assistant |

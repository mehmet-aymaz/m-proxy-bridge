# M-PROXY BRIDGE (Android) — Proje Planı

## Amaç
M-Proxy ana telefonun WiFi Direct hotspot'una (`DIRECT-mProxy...`) bağlanan
**ikinci bir Android cihazda** (tablet, yedek telefon vb.) çalışacak, küçük ve
bağımsız bir uygulama. Bu uygulama:

- Hiçbir VLESS UUID / kullanıcı girişi GEREKTİRMEZ.
- Ana telefonun zaten açtığı SOCKS5 köprüsünü (`192.168.49.1:10808`) outbound
  olarak kullanır.
- Kendi cihazında bir **TUN adaptörü** (VpnService + sing-box/libbox) açar.
- Bu sayede o cihazdaki TÜM trafik (WhatsApp, oyunlar, her uygulama) ana
  telefonun VPN tüneli üzerinden gider — manuel proxy ayarına gerek kalmaz.

Mimari, Windows Companion'da kullandığımız sing-box + TUN modeliyle birebir
aynıdır, sadece outbound hedefi VLESS sunucu değil, **ana telefonun SOCKS
köprüsü**dür.

---

## 0. GENEL KURALLAR

1. Bu, **mevcut M-Proxy Android projesinden TAMAMEN AYRI, yeni bir proje**
   olacak (örn. `m-proxy-bridge-android`). Ana M-Proxy projesine
   DOKUNULMAYACAK.
2. Tasarım basit/minimal olabilir — bu bir "yardımcı araç", ana uygulamanın
   liquid glass tasarımına bağlı değil. Tek ekran: durum + bağlan/kes butonu
   yeterli.
3. UUID/kullanıcı girişi YOK. Sabit outbound: `192.168.49.1:10808` (SOCKS5).
4. sing-box (libbox) için zaten ana M-Proxy projesinde edinilmiş `libbox.aar`
   kullanılabilir (aynı kütüphane, Android için) — sıfırdan derlemeye gerek
   yok, varsa o AAR kopyalanabilir.

---

## 1. MİMARİ

```
┌─────────────────────────────────────────┐
│  M-Proxy Bridge (bu yeni app)            │
│                                           │
│  ┌─────────────────────────────────┐    │
│  │ MainActivity (basit UI)          │    │
│  │  - Durum: Bağlı / Bağlı Değil    │    │
│  │  - Bağlan / Kes butonu           │    │
│  │  - Köprü IP/Port göstergesi      │    │
│  └──────────────┬────────────────────┘  │
│                  │                        │
│  ┌──────────────┴────────────────────┐  │
│  │ BridgeVpnService : VpnService      │  │
│  │  - TUN fd oluşturur                │  │
│  │  - libbox BoxService başlatır      │  │
│  │  - Foreground notification         │  │
│  └──────────────┬────────────────────┘  │
│                  │                        │
│  ┌──────────────┴────────────────────┐  │
│  │ libbox.aar (sing-box core)         │  │
│  │  - tun inbound                     │  │
│  │  - socks outbound (sabit)          │  │
│  └──────────────┬────────────────────┘  │
└──────────────────┼────────────────────────┘
                   │ SOCKS5
                   ▼
        192.168.49.1:10808
   (Ana telefonun WiFi Direct SOCKS köprüsü)
                   │
                   ▼
         [Ana telefon VPN tüneli → Sunucu]
```

---

## 2. PROJE İSKELETİ

```
m-proxy-bridge-android/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/mproxy/bridge/
│   │   │   ├── MainActivity.kt
│   │   │   ├── BridgeVpnService.kt
│   │   │   └── NetworkUtils.kt
│   │   └── res/
│   │       └── layout/
│   │           └── activity_main.xml
│   └── libs/
│       └── libbox.aar
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 3. sing-box CONFIG (sabit, dinamik üretim gerekmez)

`BridgeVpnService.kt` içinde, aşağıdaki JSON sabit olarak (veya basit bir
template ile) kullanılacak:

```json
{
  "log": { "level": "warn" },
  "dns": {
    "servers": [{ "address": "8.8.8.8" }],
    "strategy": "prefer_ipv4"
  },
  "inbounds": [
    {
      "type": "tun",
      "interface_name": "mproxybridge",
      "inet4_address": "172.20.0.1/30",
      "auto_route": true,
      "strict_route": true,
      "stack": "mixed",
      "sniff": true,
      "mtu": 1420
    }
  ],
  "outbounds": [
    {
      "type": "socks",
      "tag": "bridge-out",
      "server": "192.168.49.1",
      "server_port": 10808,
      "version": "5"
    },
    { "type": "direct", "tag": "direct" }
  ],
  "route": {
    "final": "bridge-out"
  }
}
```

**Notlar:**
- `stack: "mixed"` — Windows/OnePlus deneyimlerimizden, `gvisor` bazı
  cihazlarda sorun çıkarmıştı, `mixed` daha stabil.
- `mtu: 1420` — VLESS+WS+TLS üzerinden gelen trafiğin fragmentasyon sorununa
  karşı (önceki MTU 1500 sorunuyla aynı önlem).
- IPv6 route eklenmiyor (bilinçli) — ana telefonun SOCKS köprüsü zaten IPv4
  üzerinden çalışıyor, IPv6 sızıntısını engellemek için route'ta IPv6
  belirtilmiyor.

---

## 4. MainActivity.kt — Basit UI

- Tek ekran, Compose veya XML (basit `LinearLayout`/`ConstraintLayout`)
- Üstte: "M-Proxy Bridge" başlığı
- Ortada: Durum göstergesi (kırmızı nokta = Bağlı Değil, yeşil nokta = Bağlı)
- Bilgi satırı: "Köprü: 192.168.49.1:10808"
- Buton: "BAĞLAN" / "BAĞLANTIYI KES" (toggle)
- Alt bilgi: "Bu uygulama, M-Proxy ana telefonunuzun hotspot'una bağlıyken
  cihazınızın tüm internet trafiğini o tünelden geçirir."

İlk açılışta:
1. `VpnService.prepare(this)` ile izin isteği (sistem diyaloğu)
2. İzin sonrası `BridgeVpnService` başlatılabilir hale gelir

---

## 5. BridgeVpnService.kt — Akış

1. `onStartCommand` (ACTION_START):
   - Önce 192.168.49.1:10808'e bağlanılabiliyor mu kontrol et (basit socket
     connect testi, 2 saniye timeout). Bağlanılamıyorsa kullanıcıya
     "Ana telefonun M-Proxy hotspot'u bulunamadı" hatası göster, servisi
     başlatma.
   - `VpnService.Builder` ile TUN interface aç (`establish()`):
     - `addAddress("172.20.0.1", 30)`
     - `addRoute("0.0.0.0", 0)`
     - `addDnsServer("8.8.8.8")`
     - `setMtu(1420)`
   - libbox `BoxService` ile yukarıdaki sabit config'i başlat, TUN fd'yi ver.
   - Foreground notification göster ("M-Proxy Bridge Aktif").

2. `onStartCommand` (ACTION_STOP):
   - `BoxService.close()`, TUN fd kapat, `stopForeground(true)`, `stopSelf()`.

3. Periyodik kontrol (örn. her 5 saniyede bir, küçük bir coroutine/handler):
   - `192.168.49.1:10808` hâlâ erişilebilir mi?
   - Erişilemiyorsa (ana telefon hotspot'u kapattıysa) → otomatik durdur,
     bildirim göster: "Ana telefon bağlantısı kesildi."

4. Hata/crash önleme: tüm libbox çağrıları try-catch, MainActivity'ye
   broadcast/callback ile durum bildirilir.

---

## 6. AndroidManifest.xml — İzinler

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service android:name=".BridgeVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <intent-filter>
        <action android:name="android.net.VpnService"/>
    </intent-filter>
</service>
```

---

## 7. APK BOYUTU

Ana projedeki ABI filtreleme dersini buraya da uygula:

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles.addAll(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro")
                )
            )
        }
    }
}
```

---

## 8. ÇALIŞMA SIRASI (Antigravity için TODO)

```
[ ] 1. Yeni Android projesi oluştur: m-proxy-bridge-android
[ ] 2. libbox.aar'ı ana M-Proxy projesinden (app/libs/libbox.aar) kopyala
[ ] 3. AndroidManifest.xml - izinler ve servis tanımı ekle (Bölüm 6)
[ ] 4. BridgeVpnService.kt yaz (Bölüm 5) - sabit config (Bölüm 3) ile
[ ] 5. MainActivity.kt yaz (Bölüm 4) - basit UI, VPN izin akışı
[ ] 6. ABI filtreleme + ProGuard ayarları ekle (Bölüm 7)
[ ] 7. Derle: ./gradlew.bat assembleDebug
[ ] 8. Test cihazına yükle (gerçek cihaz, M-Proxy ana telefonun hotspot'una
       bağlıyken):
       - VPN izin diyaloğu çıkıyor mu?
       - "BAĞLAN" sonrası TUN adaptörü kuruluyor mu?
       - WhatsApp/Instagram gibi uygulamalarda internet çalışıyor mu?
       - Ana telefonda hotspot'u kapatınca, bu cihazda otomatik
         "bağlantı kesildi" bildirimi geliyor mu?
       - "BAĞLANTIYI KES" sonrası cihaz normal WiFi/hücresel veriye
         dönüyor mu?
```

---

## 9. ANTIGRAVITY PROMPTU

```
Yeni bir Android Studio projesi oluştur: "m-proxy-bridge-android"
(paket adı: com.mproxy.bridge). Bu proje, ana M-Proxy VPN projesinden
TAMAMEN AYRI ve bağımsız olacak, ana projeye dokunulmayacak.

Amaç: Bu uygulama, M-Proxy ana telefonun WiFi Direct hotspot'una
(DIRECT-mProxy...) bağlanan İKİNCİ bir Android cihazda çalışacak. Hiçbir
kullanıcı girişi (UUID vb.) gerektirmeyecek. Sabit olarak 192.168.49.1:10808
adresindeki SOCKS5 proxy'sini (ana telefonun VPN köprüsü) outbound olarak
kullanıp, kendi cihazında bir TUN adaptörü (VpnService) açarak TÜM trafiği
(WhatsApp, oyunlar dahil) bu köprü üzerinden tünelleyecek.

Adımlar:

1) libbox.aar dosyasını mevcut ana M-Proxy Android projesindeki
   app/libs/libbox.aar konumundan bu yeni projenin app/libs/ klasörüne
   kopyala (aynı kütüphane, yeniden derlemeye gerek yok).

2) AndroidManifest.xml'e şu izinleri ve servis tanımını ekle:
   - INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE,
     ACCESS_NETWORK_STATE, POST_NOTIFICATIONS
   - BridgeVpnService için BIND_VPN_SERVICE permission'lı, exported=false,
     android.net.VpnService action'lı intent-filter içeren servis tanımı.

3) BridgeVpnService.kt yaz (VpnService alt sınıfı):
   - ACTION_START: önce 192.168.49.1:10808'e 2 saniye timeout'lu socket
     connect testi yap. Başarısızsa kullanıcıya hata bildir (broadcast/
     callback), servisi başlatma.
   - Başarılıysa VpnService.Builder ile TUN aç:
     addAddress("172.20.0.1", 30), addRoute("0.0.0.0", 0),
     addDnsServer("8.8.8.8"), setMtu(1420).
   - libbox BoxService'i şu sabit JSON config ile başlat (TUN fd'yi config'e
     bağla):
     {
       "log": { "level": "warn" },
       "dns": { "servers": [{ "address": "8.8.8.8" }], "strategy": "prefer_ipv4" },
       "inbounds": [{
         "type": "tun", "interface_name": "mproxybridge",
         "inet4_address": "172.20.0.1/30", "auto_route": true,
         "strict_route": true, "stack": "mixed", "sniff": true, "mtu": 1420
       }],
       "outbounds": [
         { "type": "socks", "tag": "bridge-out", "server": "192.168.49.1",
           "server_port": 10808, "version": "5" },
         { "type": "direct", "tag": "direct" }
       ],
       "route": { "final": "bridge-out" }
     }
   - Foreground notification göster: "M-Proxy Bridge Aktif".
   - Arka planda 5 saniyede bir 192.168.49.1:10808 erişilebilirliğini
     kontrol eden bir döngü çalıştır - erişilemezse otomatik durdur ve
     "Ana telefon bağlantısı kesildi" bildirimi göster.
   - ACTION_STOP: BoxService.close(), TUN fd kapat, stopForeground(true),
     stopSelf().
   - Tüm libbox çağrıları try-catch ile sarılsın, hata durumunda
     MainActivity'ye broadcast ile bildirilsin, servis crash etmesin.

4) MainActivity.kt yaz - basit, tek ekranlık UI (Compose veya XML, sade
   tasarım yeterli):
   - Başlık: "M-Proxy Bridge"
   - Durum göstergesi (kırmızı/yeşil nokta + "Bağlı Değil"/"Bağlı" yazısı)
   - Bilgi satırı: "Köprü: 192.168.49.1:10808"
   - "BAĞLAN" / "BAĞLANTIYI KES" toggle butonu
   - Alt açıklama metni: "Bu uygulama, M-Proxy ana telefonunuzun
     hotspot'una bağlıyken cihazınızın tüm internet trafiğini o tünelden
     geçirir. WhatsApp ve diğer uygulamalar da çalışır."
   - İlk "BAĞLAN" tıklamasında VpnService.prepare(this) ile sistem izin
     diyaloğu tetiklensin, izin onaylanınca BridgeVpnService başlatılsın.
   - BridgeVpnService'den gelen durum broadcast'leri dinlenip UI
     güncellensin (bağlı/bağlanıyor/hata/bağlantı kesildi).

5) build.gradle.kts (app modülü):
   - ABI filtreleme: ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a"),
     splits.abi ile isUniversalApk = false.
   - Release build: isMinifyEnabled = true, isShrinkResources = true,
     proguardFiles.addAll(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), file("proguard-rules.pro"))).

6) Derle: ./gradlew.bat assembleDebug, hata varsa düzelt.

7) Test cihazına yükle ve şu senaryoları doğrula:
   - VPN izin diyaloğu çıkıyor mu?
   - "BAĞLAN" sonrası TUN adaptörü kuruluyor mu (sistem VPN ikonu görünüyor mu)?
   - Ana telefonun M-Proxy hotspot'una bağlıyken, bu cihazda WhatsApp/
     Instagram gibi uygulamalarda internet çalışıyor mu?
   - Ana telefonda hotspot kapatılınca bu cihazda otomatik "bağlantı
     kesildi" bildirimi geliyor mu ve TUN adaptörü kapanıyor mu?
   - "BAĞLANTIYI KES" sonrası cihaz normal ağına dönüyor mu?

Sonuçları ve hata loglarını raporla.
```

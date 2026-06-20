# 🌉 M-Proxy Bridge (Android Client)

[English](#english) | [Türkçe](#türkçe)

---

## English

M-Proxy Bridge is a lightweight, high-performance client app for Android. It connects to the Wi-Fi Direct Hotspot of the primary device running **M-Proxy VPN** and routes all network traffic of the client device securely through the bridge.

### ✨ Key Features
*   **Zero Configuration:** No VLESS UUID or user login required. Outbound connection is pre-configured to point to the primary device's SOCKS5 bridge (`192.168.49.1:10808`).
*   **Low Latency & High Performance:** Special optimization rules including TCP Fast Open (TFO), custom MTU limits, and low-latency Wi-Fi locks to ensure extremely snappy browsing.
*   **Instant WhatsApp Delivery:** Includes a continuous UDP Keep-Alive/Ping background loop and strict IPv4 routing to prevent WhatsApp message delay notifications and connection timeout issues.
*   **Low-Latency DNS:** Integrated sing-box FakeIP DNS resolution to accelerate domain lookup speeds.
*   **Beautiful UI & widgets:** Features an interactive glassmorphic interface with a live speedtest, real-time traffic statistics, and a home-screen widget for quick controls.

### 📱 How to Use
1. Install the APK on the second device.
2. Connect to the **Wi-Fi Direct Hotspot** of the primary device running the VPN.
3. Open the app and tap the main **Start Connection** button.
4. Once the state changes to **ACTIVE**, all your traffic will be secure and routed through the bridge.

---

## Türkçe

M-Proxy Bridge, ikinci cihazınızda (tablet veya yedek telefon) çalışarak ana cihazınızdaki **M-Proxy VPN** ağına Wi-Fi Direct (Hotspot) üzerinden bağlanan ve tüm internet trafiğinizi tünelleyen hafif ve yüksek performanslı bir istemcidir.

### ✨ Öne Çıkan Özellikler
*   **Sıfır Yapılandırma:** VLESS UUID veya kullanıcı girişi gerektirmez. Çıkış bağlantısı sabit olarak ana cihazın oluşturduğu SOCKS5 köprüsüne (`192.168.49.1:10808`) yönlendirilir.
*   **Düşük Gecikme ve Yüksek Performans:** TCP Fast Open (TFO), optimize edilmiş MTU sınırları ve kesintisiz ağ bağlantısı için düşük gecikmeli Wi-Fi kilitleri gibi gelişmiş ayarlar.
*   **Gecikmesiz WhatsApp Deneyimi:** 300ms aralıklarla çalışan UDP Keep-Alive/Ping döngüsü ve IPv4 tünel yönlendirmesi sayesinde WhatsApp mesaj gecikmelerini ve "bağlantı kontrol ediliyor" bildirimlerini tamamen ortadan kaldırır.
*   **Hızlı DNS:** sing-box'ın FakeIP DNS mimarisiyle entegre edilerek internet aramalarının gecikmesiz çözümlenmesini sağlar.
*   **Şık Tasarım ve Widget Desteği:** Canlı hız testi, gerçek zamanlı trafik göstergeleri ve ana ekrandan kontrol etmenizi sağlayan ekran widget'ı.

### 📱 Kullanım Adımları
1. APK dosyasını ikinci cihazınıza kurun.
2. Ana cihazda açık olan **Wi-Fi Direct Hotspot** ağına bağlanın.
3. Uygulamayı açıp ortadaki büyük **Bağlantıyı Başlat** butonuna dokunun.
4. Durum **AKTİF** olduğunda cihazınızdaki tüm internet trafiği köprü üzerinden tünellenecektir.

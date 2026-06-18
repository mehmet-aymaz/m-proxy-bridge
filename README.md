# 🌉 M-Proxy Bridge

[![Android Platform](https://img.shields.io/badge/Platform-Android-green.svg?logo=android&logoColor=white)](https://developer.android.com/)
[![Sing-Box Core](https://img.shields.io/badge/Core-Sing--Box-blue.svg)](https://github.com/SagerNet/sing-box)
[![License](https://img.shields.io/badge/License-Private-red.svg)](#)

A high-performance Android VPN client designed to route system-wide traffic through an M-Proxy VPN host over Wi-Fi Direct.

---

## 🇹🇷 Türkçe Proje Açıklaması

**M-Proxy Bridge**, ana cihazda çalışan **M-Proxy VPN** sunucusuna Wi-Fi Direct (Hotspot) aracılığıyla bağlanarak tüm cihaz trafiğini tünelleyen hafif ve yüksek performanslı bir VPN köprü istemcisidir. Cihazda yerel bir TUN arayüzü (`tun0`) oluşturur ve gelen bağlantıları SOCKS5 protokolü üzerinden ana telefona aktarır.

### ✨ Temel Özellikler
- **Wi-Fi Direct Tünelleme:** Ana cihazdaki M-Proxy VPN hotspot ağına bağlanarak güvenli internet paylaşımı sağlar.
- **Düşük Latanslı TCP DNS:** DNS sorgularını proxy üzerinden TCP (`tcp://8.8.8.8`) kullanarak çözer. Bu sayede UDP tabanlı DNS sorgularının Wi-Fi Direct üzerindeki paket kayıpları önlenir.
- **WhatsApp Gecikme Önleyici (UDP Bloklama):** SOCKS5 proxy üzerinden UDP paketlerinin zaman aşımını beklemek yerine, DNS dışındaki tüm UDP paketleri yerel düzeyde anında engellenir (`"network": "udp" -> "block-out"`). Bu durum WhatsApp gibi servislerin bekleme yapmadan **milisaniyeler içinde** TCP protokolüne geri dönerek mesajları anında iletmesini sağlar.
- **MTU Optimizasyonu (`1360`):** Wi-Fi Direct ve SOCKS5 tünel katmanlarının oluşturduğu ek yük nedeniyle oluşan paket parçalanmalarını engellemek için TUN arayüzünün MTU değeri `1360` olarak optimize edilmiştir.
- **Modern Glassmorphism Arayüzü:** Akıcı animasyonlar, dahili hız testi (speedtest), gecikme (ping) ölçümü ve detaylı bağlantı istatistikleri.
- **Esnek Widget Desteği:** Ana ekrandan tek dokunuşla köprüyü başlatıp durdurabilen şık widget arayüzü.

---

## 🇬🇧 English Project Description

**M-Proxy Bridge** is a lightweight, high-performance Android VPN client built to bridge local device traffic to an **M-Proxy VPN** host running on a Wi-Fi Direct Hotspot network. It registers a local TUN interface (`tun0`) and detours all non-private network traffic to the host's mixed HTTP/SOCKS5 proxy port `10808`.

### ✨ Key Features
- **Wi-Fi Direct Bridging:** Routes system-wide traffic through the hotspot proxy seamlessly.
- **Low-Latency TCP DNS:** Resolves DNS queries via TCP (`tcp://8.8.8.8`), preventing UDP packet drops over standard SOCKS5 Wi-Fi Direct bridges.
- **WhatsApp Lag Fix (UDP Blocking):** Blocks non-DNS UDP packets locally (`"network": "udp" -> "block-out"`) to force chat applications (like WhatsApp) to instantly fall back to TCP, resolving the 3-5 second message sending delay.
- **Optimized MTU (`1360`):** Fine-tuned TUN MTU to prevent packet fragmentation over nested SOCKS5 and Wi-Fi Direct layers.
- **Sleek Glassmorphism Interface:** Responsive layout featuring built-in speed tests, real-time ping monitors, and data logs.
- **App Widget Support:** Start/stop the bridge directly from your Android home screen with a responsive orb widget.

---

## 🛠️ Proje Yapısı / Project Structure

```
M-Proxy Bridge/
├── m-proxy-bridge-android/      # Android Bridge client source code
├── build_outputs/               # Pre-compiled stable production APKs
└── M-PROXY_BRIDGE_PLAN.md       # Development logs and architectural plan
```

### 📱 Android Uygulaması Gereksinimleri
- **Minimum SDK:** Android 24 (Nougat 7.0)
- **Target SDK:** Android 34 (Android 14)
- **Derleme Aracı:** Gradle 8.2+ & Kotlin 1.9+

---

## 📄 Lisans / License

Bu proje özel olarak geliştirilmiştir. İzinsiz kopyalanamaz veya dağıtılamaz. All rights reserved.

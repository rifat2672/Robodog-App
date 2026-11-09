# Robo Dog — Android App

ESP32 robot dog কে ফোন থেকে **WiFi** বা **Bluetooth LE** দিয়ে চালানোর app (Java, কোনো বাড়তি library নেই)।
ESP32 এ `ESP32_RoboDog_App.ino` firmware লাগবে।

## চালানো
1. Android Studio → **File → Open** → এই `RoboDogApp` ফোল্ডার বেছে নাও।
2. Gradle sync শেষ হলে (প্রথমবার internet লাগবে) ফোন USB দিয়ে লাগিয়ে **Run ▶** চাপো।
   - SDK 34 না থাকলে Android Studio নিজেই install করতে বলবে।
   - AGP upgrade করতে বললে করতে পারো, না করলেও চলবে।

## ব্যবহার
- **WiFi:** ফোনকে ESP32 এর `RoboDog` হটস্পটে (পাসওয়ার্ড `12345678`) লাগাও, IP `192.168.4.1` রেখে "সংযোগ করো"।
  ESP32 রাউটারে থাকলে ফোনও একই রাউটারে রাখো, "খোঁজো" চাপলে IP নিজেই বসে যাবে।
- **Bluetooth:** "Bluetooth" বেছে "সংযোগ করো"। Pairing লাগবে না, app নিজেই `RoboDog` খুঁজে নেবে।
  প্রথমবার Bluetooth permission চাইবে, Allow দিও।
- দিকের বাটন চেপে ধরে রাখলে হাঁটবে, ছাড়লেই থামবে।

## ফাইল
| ফাইল | কাজ |
|---|---|
| `MainActivity.java` | UI, বাটন, ক্যালিব্রেশন |
| `DogLink.java` | WiFi/Bluetooth এর common interface |
| `WifiLink.java` | TCP port 8888 + UDP 8889 দিয়ে খোঁজা |
| `BleLink.java` | BLE scan, Nordic UART Service |

## কমান্ড (ESP32 এর সাথে একই)
`F` `B` `L` `R` `S`, `SPD:1..10`, `STR:10..45`, `GAIT:0|1`, `ACT:stand|sit|lie|hi`,
`TRIM:<পা>:<মান>`, `INV:<পা>:<0|1>`, `CAL:<ডিগ্রি>`, `RST`, `?`
উত্তর: `ST:speed,stride,gait,trim0..3,inv0..3`

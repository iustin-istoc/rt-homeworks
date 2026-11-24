// Setările tracker-ului de autobuz și numele topic-urilor MQTT.
//
// Brokerul este partajat de toți studenții. STUDENT trebuie să fie identic cu
// STUDENT din config.properties al aplicației Java.

#pragma once

#include <Arduino.h>

// Rețeaua Wi-Fi (doar 2.4 GHz). Nu urcați pe GitHub parola rețelei voastre:
// înainte de commit, lăsați aici valorile pentru "iot-demo".
const char* const WIFI_SSID = "iPhone";
const char* const WIFI_PASSWORD = "12345678";

const char* const BROKER_URI = "mqtt://control.aut.utcluj.ro:11188";
const char* const NTP_SERVER = "pool.ntp.org";

const char* const STUDENT = "istoc-iustin";

// "esp32-" + ultimele 6 cifre hexa ale adresei MAC (citit în connection.cpp)
extern const char* const DEVICE_ID;

// Pinii plăcii ESP32-C3 Super Mini
const int BUTTON_PIN = 9;  // butonul BOOT: LOW cât timp este apăsat
const int LED_PIN = 8;     // LED-ul albastru: aprins pe LOW

// Perioadele taskurilor periodice
const uint32_t LED_PERIOD_MS = 50;        // taskul LED: redă tiparul de clipire
const uint32_t TRACKER_PERIOD_MS = 1000;  // taskul tracker: numărătoarea locală și modul

// la al câtelea job al tracker-ului se publică "status" (5 => la fiecare 5 s)
const uint32_t STATUS_EVERY_JOBS = 5;

// Pragurile modurilor (secunde până când utilizatorul trebuie să plece spre stație)
const int32_t SOON_THRESHOLD_S = 120;  // sub 2 minute: pregătește-te

// Fără nicio comandă TRACK/NOBUS atâta timp, datele sunt considerate vechi
const uint32_t STALE_AFTER_MS = 30000;

// Cât rămâne afișată confirmarea urcării (BOARDED) înainte de IDLE
const uint32_t BOARDED_SHOW_MS = 4000;

// intervalul minim între sosiri pentru taskul sporadic al butonului
const uint32_t MIN_INTERARRIVAL_MS = 1000;

// Scrie în buf topic-ul "ssatr/<STUDENT>/devices/<DEVICE_ID>/<kind>". kind este:
//   "commands"  aplicația Java -> placă: TRACK / NOBUS / IDLE / ACK
//   "status"    placă -> aplicația Java: "deviceId;timestamp;MOD;plecareS"
//   "events"    placă -> aplicația Java: "deviceId;timestamp;BOARDED"
inline void make_topic(char* buf, size_t size, const char* kind) {
    snprintf(buf, size, "ssatr/%s/devices/%s/%s", STUDENT, DEVICE_ID, kind);
}

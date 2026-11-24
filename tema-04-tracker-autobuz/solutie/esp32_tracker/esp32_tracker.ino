// Tracker personal de autobuz pe ESP32-C3 Super Mini.
//
// Aplicația Java urmărește în timp real linia și stația alese de utilizator și
// îi trimite plăcii, prin MQTT (topic "commands"), cât mai are autobuzul până
// la stație și în câte secunde trebuie să plece utilizatorul spre ea. Placa
// arată asta pe LED; utilizatorul confirmă urcarea în autobuz cu butonul BOOT.
//
//   command_received --(coada comenzilor)--> task aperiodic: comenzi ----+
//                                                                        v
//   ISR buton BOOT --(coada butonului)--> task sporadic: buton --> [ starea ] <-- mutex
//        |                                      |                       |
//        |                         "events": BOARDED                     v
//        |                                                   task periodic tracker (1 s)
//        |                                                   modul, numărătoarea locală,
//        |                                                   "status" la fiecare 5 s
//        |                                                              |
//        |                                                    (modul LED, atomic)
//        |                                                              v
//        +---------------------------------------------- task periodic LED (50 ms)
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include <atomic>

#include "config.h"
#include "connection.h"

// ---------------------------------------------------------------------------
// Modurile tracker-ului = ce arată LED-ul
// ---------------------------------------------------------------------------
enum Mode : uint8_t {
    MODE_IDLE,     // nu urmărește nimic                       LED stins
    MODE_NOBUS,    // linia aleasă, dar niciun autobuz spre stație   un flash scurt la 3 s
    MODE_STALE,    // nu mai vin date de la aplicație (> 30 s)  două flash-uri la 2 s
    MODE_FAR,      // e timp: plecarea spre stație > 2 min     clipire rară (200 ms / 2 s)
    MODE_SOON,     // pleacă în mai puțin de 2 minute          clipire 2 Hz
    MODE_GO,       // trebuie să plece acum spre stație        aprins continuu
    MODE_ARRIVED,  // autobuzul este în stație                 clipire foarte rapidă
    MODE_BOARDED,  // utilizatorul a confirmat urcarea         trei flash-uri, apoi stins
};
const char* const MODE_NAMES[] = {"IDLE", "NOBUS", "STALE", "FAR", "SOON", "GO", "ARRIVED", "BOARDED"};

// ---------------------------------------------------------------------------
// Starea partajată, protejată de state_mutex
// ---------------------------------------------------------------------------
struct TrackerState {
    bool tracking;          // există o urmărire activă (TRACK sau NOBUS primit, fără IDLE)
    bool bus_found;         // ultima comandă a fost TRACK (true) sau NOBUS (false)
    char line[12];          // linia urmărită, de exemplu "24B"
    int32_t eta_s;          // sosirea autobuzului în stație, la momentul update_ms
    int32_t leave_s;        // în câte secunde trebuie să plece utilizatorul, la update_ms
    int32_t stops;          // câte stații mai are autobuzul până la stația aleasă
    int32_t dist_m;         // distanța pe traseu până la stație
    uint32_t update_ms;     // millis() la ultima comandă TRACK/NOBUS
    bool boarded;           // utilizatorul a confirmat urcarea
    uint32_t boarded_ms;    // millis() la confirmare
};

static TrackerState state;
static SemaphoreHandle_t state_mutex = nullptr;

// Modul curent, scris de tracker și citit de taskul LED la fiecare 50 ms.
// Un singur octet: atomic, fără mutex.
static std::atomic<uint8_t> led_mode{MODE_IDLE};

// Cozile: elementele conțin datele însele, nu pointeri.
struct CommandMsg {
    char text[COMMAND_MAX_LEN];
};
struct ButtonPress {
    uint32_t t_ms;  // momentul apăsării, pus în coadă de ISR
};
static QueueHandle_t command_queue = nullptr;
static QueueHandle_t button_queue = nullptr;

// Priorități (2..19). Periodicele după Rate Monotonic: LED (50 ms) > tracker (1 s).
// Butonul (sporadic, interval minim 1 s, job foarte scurt) are prioritatea cea mai
// mare, ca utilizatorul să vadă imediat confirmarea. Comenzile (aperiodic, sosesc
// cam la 5 s) au prioritatea cea mai mică: nu au termen strict.
const UBaseType_t PRIO_BUTTON = 6;
const UBaseType_t PRIO_LED = 5;
const UBaseType_t PRIO_TRACKER = 4;
const UBaseType_t PRIO_COMMAND = 3;
const uint32_t TASK_STACK = 4096;

static inline uint32_t ticks_to_ms(TickType_t t) {
    return (uint32_t)((uint64_t)t * 1000 / configTICK_RATE_HZ);
}

// ---------------------------------------------------------------------------
// Task aperiodic: comenzile de la aplicația Java
//   TRACK;<linie>;<etaS>;<plecareS>;<statii>;<distM>
//   NOBUS;<linie>
//   IDLE
//   ACK;<vehicul>
// ---------------------------------------------------------------------------
static void command_task(void*) {
    CommandMsg cmd;
    for (;;) {
        if (xQueueReceive(command_queue, &cmd, portMAX_DELAY) != pdTRUE) continue;

        char* save = nullptr;
        char* kind = strtok_r(cmd.text, ";", &save);
        if (kind == nullptr) continue;
        uint32_t now = millis();

        if (strcmp(kind, "TRACK") == 0 || strcmp(kind, "NOBUS") == 0) {
            bool found = strcmp(kind, "TRACK") == 0;
            const char* line = strtok_r(nullptr, ";", &save);
            int32_t v[4] = {0, 0, 0, 0};
            int fields = 0;
            if (found) {
                for (; fields < 4; fields++) {
                    const char* f = strtok_r(nullptr, ";", &save);
                    if (f == nullptr) break;
                    v[fields] = atoi(f);
                }
                if (fields < 4) {
                    Serial.println("[comenzi] TRACK incomplet, ignorat");
                    continue;
                }
            }
            bool ignored = false;
            xSemaphoreTake(state_mutex, portMAX_DELAY);
            // o comandă TRACK rămasă pe drum după confirmarea urcării nu repornește urmărirea
            if (state.boarded && now - state.boarded_ms < BOARDED_SHOW_MS) {
                ignored = true;
            } else {
                state.boarded = false;
                state.tracking = true;
                state.bus_found = found;
                strlcpy(state.line, line ? line : "?", sizeof(state.line));
                state.eta_s = v[0];
                state.leave_s = v[1];
                state.stops = v[2];
                state.dist_m = v[3];
                state.update_ms = now;
            }
            xSemaphoreGive(state_mutex);
            if (ignored) Serial.println("[comenzi] urcare confirmată, comanda ignorată");
        } else if (strcmp(kind, "IDLE") == 0) {
            xSemaphoreTake(state_mutex, portMAX_DELAY);
            state.tracking = false;
            xSemaphoreGive(state_mutex);
            Serial.println("[comenzi] IDLE: urmărire oprită");
        } else if (strcmp(kind, "ACK") == 0) {
            const char* label = strtok_r(nullptr, ";", &save);
            Serial.printf("[comenzi] aplicația a confirmat urcarea în vehiculul %s\n", label ? label : "?");
        } else {
            Serial.printf("[comenzi] comandă necunoscută: %s\n", kind);
        }
    }
}

// ---------------------------------------------------------------------------
// Task periodic tracker (T = 1 s): numărătoarea locală și modul
// ---------------------------------------------------------------------------
static void tracker_task(void*) {
    const TickType_t period = pdMS_TO_TICKS(TRACKER_PERIOD_MS);
    TickType_t last_wake = xTaskGetTickCount();
    uint32_t job = 0;
    Mode last_mode = MODE_IDLE;

    for (;;) {
        xSemaphoreTake(state_mutex, portMAX_DELAY);
        TrackerState s = state;
        xSemaphoreGive(state_mutex);
        uint32_t now = millis();

        // Între două comenzi (cam la 5 s), placa scade singură timpul rămas, ca
        // LED-ul să treacă la timp în SOON / GO chiar dacă o comandă întârzie.
        int32_t leave = s.leave_s - (int32_t)((now - s.update_ms) / 1000);

        Mode m;
        if (s.boarded && now - s.boarded_ms < BOARDED_SHOW_MS) m = MODE_BOARDED;
        else if (!s.tracking) m = MODE_IDLE;
        else if (now - s.update_ms > STALE_AFTER_MS) m = MODE_STALE;
        else if (!s.bus_found) m = MODE_NOBUS;
        else if (s.stops == 0) m = MODE_ARRIVED;
        else if (leave <= 0) m = MODE_GO;
        else if (leave <= SOON_THRESHOLD_S) m = MODE_SOON;
        else m = MODE_FAR;
        led_mode.store(m);

        if (m != last_mode) {
            Serial.printf("[tracker] mod %s -> %s (linia %s, plecare în %ld s, %ld stații)\n",
                          MODE_NAMES[last_mode], MODE_NAMES[m], s.line, (long)leave, (long)s.stops);
            last_mode = m;
        }

        if (++job % STATUS_EVERY_JOBS == 0) {
            char msg[96];
            snprintf(msg, sizeof(msg), "%s;%lld;%s;%ld", DEVICE_ID, (long long)epoch_ms(),
                     MODE_NAMES[m], (long)(s.tracking && s.bus_found ? leave : 0));
            mqtt_publish("status", msg);
        }

        xTaskDelayUntil(&last_wake, period);
    }
}

// ---------------------------------------------------------------------------
// Task periodic LED (T = 50 ms): redă tiparul modului curent
// ---------------------------------------------------------------------------
static bool led_on_at(Mode m, uint32_t t) {
    switch (m) {
        case MODE_IDLE:    return false;
        case MODE_NOBUS:   return t % 3000 < 50;
        case MODE_STALE:   { uint32_t p = t % 2000; return p < 50 || (p >= 250 && p < 300); }
        case MODE_FAR:     return t % 2000 < 200;
        case MODE_SOON:    return t % 500 < 250;
        case MODE_GO:      return true;
        case MODE_ARRIVED: return t % 150 < 75;
        case MODE_BOARDED: { uint32_t p = t % 1000; return p < 100 || (p >= 200 && p < 300) || (p >= 400 && p < 500); }
    }
    return false;
}

static void led_task(void*) {
    const TickType_t period = pdMS_TO_TICKS(LED_PERIOD_MS);
    TickType_t last_wake = xTaskGetTickCount();
    uint32_t t = 0;  // timpul tiparului, avansează cu o perioadă la fiecare job
    Mode prev = MODE_IDLE;

    for (;;) {
        Mode m = (Mode)led_mode.load();
        if (m != prev) {  // tiparul nou începe de la capăt
            t = 0;
            prev = m;
        }
        digitalWrite(LED_PIN, led_on_at(m, t) ? LOW : HIGH);  // aprins pe LOW
        t += LED_PERIOD_MS;
        xTaskDelayUntil(&last_wake, period);
    }
}

// ---------------------------------------------------------------------------
// Task sporadic: butonul BOOT = „am urcat în autobuz”
// ---------------------------------------------------------------------------
static void button_task(void*) {
    ButtonPress press;
    bool have_accepted = false;
    uint32_t last_accepted_ms = 0;

    for (;;) {
        if (xQueueReceive(button_queue, &press, portMAX_DELAY) != pdTRUE) continue;

        // intervalul minim între sosiri: filtrează și bounce-ul contactelor
        if (have_accepted && press.t_ms - last_accepted_ms < MIN_INTERARRIVAL_MS) continue;
        have_accepted = true;
        last_accepted_ms = press.t_ms;

        bool was_tracking;
        char line[12];
        xSemaphoreTake(state_mutex, portMAX_DELAY);
        was_tracking = state.tracking;
        strlcpy(line, state.line, sizeof(line));
        if (was_tracking) {
            state.tracking = false;
            state.boarded = true;
            state.boarded_ms = millis();
        }
        xSemaphoreGive(state_mutex);

        if (!was_tracking) {
            Serial.println("[buton] nu urmăresc nicio linie, apăsare ignorată");
            continue;
        }
        led_mode.store(MODE_BOARDED);  // confirmarea vizuală imediat, fără să aștepte tracker-ul

        char msg[96];
        snprintf(msg, sizeof(msg), "%s;%lld;BOARDED", DEVICE_ID, (long long)epoch_ms());
        mqtt_publish("events", msg);
        Serial.printf("[buton] urcare confirmată (linia %s), trimis BOARDED\n", line);
    }
}

// Rutina de întrerupere a butonului BOOT: doar pune apăsarea în coadă.
void IRAM_ATTR button_isr(void) {
    ButtonPress press;
    press.t_ms = ticks_to_ms(xTaskGetTickCountFromISR());
    BaseType_t woken = pdFALSE;
    xQueueSendFromISR(button_queue, &press, &woken);
    portYIELD_FROM_ISR(woken);
}

// Apelată de clientul MQTT pentru fiecare comandă: doar o pune în coadă.
void command_received(const char* text) {
    CommandMsg cmd;
    strlcpy(cmd.text, text, sizeof(cmd.text));
    if (xQueueSend(command_queue, &cmd, 0) != pdTRUE) {
        Serial.println("[mqtt] coada comenzilor este plină, comandă pierdută");
    }
}

void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);
    delay(200);
    Serial.println();
    Serial.printf("=== tema 04: tracker autobuz, STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH);
    pinMode(BUTTON_PIN, INPUT_PULLUP);

    wifi_connect();
    time_sync();

    // mutexul și cozile, înainte de mqtt_start()
    memset(&state, 0, sizeof(state));
    strlcpy(state.line, "-", sizeof(state.line));
    state_mutex = xSemaphoreCreateMutex();
    command_queue = xQueueCreate(8, sizeof(CommandMsg));
    button_queue = xQueueCreate(16, sizeof(ButtonPress));
    if (!state_mutex || !command_queue || !button_queue) {
        Serial.println("EROARE: nu s-au putut crea mutexul sau cozile");
        for (;;) delay(1000);
    }

    mqtt_start();

    xTaskCreate(led_task, "led", TASK_STACK, nullptr, PRIO_LED, nullptr);
    xTaskCreate(tracker_task, "tracker", TASK_STACK, nullptr, PRIO_TRACKER, nullptr);
    xTaskCreate(command_task, "commands", TASK_STACK, nullptr, PRIO_COMMAND, nullptr);
    xTaskCreate(button_task, "button", TASK_STACK, nullptr, PRIO_BUTTON, nullptr);
    attachInterrupt(digitalPinToInterrupt(BUTTON_PIN), button_isr, FALLING);
    Serial.println("taskurile au pornit; aștept o linie de la aplicația Java");
}

void loop() {
    delay(1000);
}

// Semafor pentru pietoni, cu buton, pe ESP32-C3 Super Mini.
//
// Placa este controlerul semaforului; lămpile sunt desenate de dashboard, care
// le aprinde după mesajele "lights" publicate de placă. Patru activități:
//
//   ISR buton BOOT ----+
//                      +--(coada butonului)--> task sporadic
//   command_received --+   (BUTTON)                 |
//                                                   v
//                                         [ starea semaforului ] <-- mutex
//                                            ^          |
//                                            |          v
//                         task periodic de control   task periodic de ieșire
//                         (fazele, T = 100 ms)       (lămpile, T = 500 ms) --> lights
//
// Gata implementate (nu trebuie modificate):
//   config.h         setările: Wi-Fi, broker, STUDENT, pinii, perioadele, duratele fazelor
//   connection.h/cpp Wi-Fi, ora exactă (SNTP), clientul MQTT, mqtt_publish(), epoch_ms()
//
// Așa cum este, schița se conectează la Wi-Fi și la broker și afișează pe
// Serial comenzile primite. Restul îl scrieți voi: TODO 0 ... TODO 3.
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include "config.h"
#include "connection.h"

// Fazele semaforului (vezi tabelul și diagrama din README.md). Numele din
// PHASE_NAMES sunt cele din mesajul "lights", în aceeași ordine ca în enum.
enum Phase { CAR_GREEN, CAR_YELLOW, ALL_RED, PED_GREEN };
const char* const PHASE_NAMES[] = {"CAR_GREEN", "CAR_YELLOW", "ALL_RED", "PED_GREEN"};

// Culorile unei lămpi, cu numele din mesajul "lights".
enum Light { RED, YELLOW, GREEN };
const char* const LIGHT_NAMES[] = {"RED", "YELLOW", "GREEN"};

// ---------------------------------------------------------------------------
// TODO 0 (c): STAREA SEMAFORULUI, protejată de state_mutex
// ---------------------------------------------------------------------------
struct TrafficState {
    Phase phase;              // faza curentă
    uint32_t phase_start_ms;  // millis() la intrarea în faza curentă
    bool ped_request;         // cererea pietonului (lampa AȘTEPTAȚI)
};

static TrafficState state;
static SemaphoreHandle_t state_mutex = nullptr;

// Elementul cozii butonului: momentul apăsării (ms, din tick-urile FreeRTOS,
// monoton) și sursa ei. Datele însele, nu pointeri.
enum PressSource : uint8_t { SRC_BOOT, SRC_DASHBOARD };
struct ButtonPress {
    uint32_t t_ms;
    PressSource source;
};

static QueueHandle_t button_queue = nullptr;
const UBaseType_t BUTTON_QUEUE_LEN = 16;

// Priorități (între 2 și 19). Cele două periodice după Rate Monotonic:
// control (T = 100 ms) > ieșire (T = 500 ms). Taskul sporadic are prioritatea
// cea mai mare: un job este foarte scurt (o comparație și o scriere sub mutex),
// iar intervalul minim între sosiri (1 s) îi limitează încărcarea, deci nu
// poate înfometa taskurile periodice, dar cererea apare imediat în stare.
const UBaseType_t PRIO_SPORADIC = 6;
const UBaseType_t PRIO_CONTROL = 5;
const UBaseType_t PRIO_OUTPUT = 4;
const uint32_t TASK_STACK = 4096;

static inline uint32_t ticks_to_ms(TickType_t t) {
    return (uint32_t)((uint64_t)t * 1000 / configTICK_RATE_HZ);
}

// ---------------------------------------------------------------------------
// TODO 1: TASK PERIODIC DE CONTROL (T = CONTROL_PERIOD_MS)
// ---------------------------------------------------------------------------
static void control_task(void*) {
    const TickType_t period = pdMS_TO_TICKS(CONTROL_PERIOD_MS);
    TickType_t last_wake = xTaskGetTickCount();  // r_0

    for (;;) {
        bool changed = false;
        Phase new_phase;

        xSemaphoreTake(state_mutex, portMAX_DELAY);
        uint32_t now = millis();
        uint32_t elapsed = now - state.phase_start_ms;  // corect și la depășirea lui millis()
        Phase next = state.phase;
        switch (state.phase) {
            case CAR_GREEN:
                if (elapsed >= CAR_GREEN_MIN_MS && state.ped_request) next = CAR_YELLOW;
                break;
            case CAR_YELLOW:
                if (elapsed >= CAR_YELLOW_MS) next = ALL_RED;
                break;
            case ALL_RED:
                if (elapsed >= ALL_RED_MS) next = PED_GREEN;
                break;
            case PED_GREEN:
                if (elapsed >= PED_GREEN_MS) next = CAR_GREEN;
                break;
        }
        if (next != state.phase) {
            state.phase = next;
            state.phase_start_ms = now;
            if (next == PED_GREEN) state.ped_request = false;  // pietonul primește ce a cerut
            changed = true;
        }
        new_phase = state.phase;
        xSemaphoreGive(state_mutex);

        if (changed) {
            Serial.printf("[control] %lu ms: faza %s\n", (unsigned long)millis(), PHASE_NAMES[new_phase]);
        }

        // eliberarea următoare la r_k = r_0 + kT, fără derivă
        xTaskDelayUntil(&last_wake, period);
    }
}

// ---------------------------------------------------------------------------
// TODO 2: TASK PERIODIC DE IEȘIRE (T = OUTPUT_PERIOD_MS)
// ---------------------------------------------------------------------------
static void output_task(void*) {
    const TickType_t period = pdMS_TO_TICKS(OUTPUT_PERIOD_MS);
    TickType_t last_wake = xTaskGetTickCount();

    for (;;) {
        // 1. copia stării, cu mutexul ținut cât mai puțin
        xSemaphoreTake(state_mutex, portMAX_DELAY);
        TrafficState s = state;
        xSemaphoreGive(state_mutex);
        uint32_t now = millis();

        // 2. lămpile, din faza copiată
        Light cars = RED;
        Light peds = RED;
        switch (s.phase) {
            case CAR_GREEN:  cars = GREEN;  peds = RED;   break;
            case CAR_YELLOW: cars = YELLOW; peds = RED;   break;
            case ALL_RED:    cars = RED;    peds = RED;   break;
            case PED_GREEN:  cars = RED;    peds = GREEN; break;
        }

        // 3. numărătoarea inversă, rotunjită în sus; 0 în afara PED_GREEN
        uint32_t seconds = 0;
        if (s.phase == PED_GREEN) {
            uint32_t elapsed = now - s.phase_start_ms;
            uint32_t remaining = elapsed < PED_GREEN_MS ? PED_GREEN_MS - elapsed : 0;
            seconds = (remaining + 999) / 1000;
            // controlul poate trece în CAR_GREEN cu până la o perioadă mai târziu:
            // cât timp faza este încă PED_GREEN, numărătoarea rămâne afișată
            if (seconds == 0) seconds = 1;
        }

        // 4. mesajul "lights" și LED-ul plăcii
        char msg[128];
        snprintf(msg, sizeof(msg), "%s;%lld;%s;%s;%s;%d;%lu",
                 DEVICE_ID, (long long)epoch_ms(), PHASE_NAMES[s.phase],
                 LIGHT_NAMES[cars], LIGHT_NAMES[peds], s.ped_request ? 1 : 0,
                 (unsigned long)seconds);
        mqtt_publish("lights", msg);
        digitalWrite(LED_PIN, s.ped_request ? LOW : HIGH);  // aprins pe LOW

        xTaskDelayUntil(&last_wake, period);
    }
}

// ---------------------------------------------------------------------------
// TODO 3: TASK SPORADIC (apăsările butonului)
// ---------------------------------------------------------------------------
static void button_task(void*) {
    ButtonPress press;
    bool have_accepted = false;
    uint32_t last_accepted_ms = 0;

    for (;;) {
        // blocat până la sosirea unei apăsări
        if (xQueueReceive(button_queue, &press, portMAX_DELAY) != pdTRUE) continue;

        const char* src = press.source == SRC_BOOT ? "BOOT" : "dashboard";
        uint32_t since = press.t_ms - last_accepted_ms;
        if (have_accepted && since < MIN_INTERARRIVAL_MS) {
            Serial.printf("[buton] apăsare %s respinsă: la %lu ms de ultima acceptată (minim %lu ms)\n",
                          src, (unsigned long)since, (unsigned long)MIN_INTERARRIVAL_MS);
            continue;
        }
        have_accepted = true;
        last_accepted_ms = press.t_ms;

        bool already;
        xSemaphoreTake(state_mutex, portMAX_DELAY);
        already = state.ped_request;
        state.ped_request = true;
        xSemaphoreGive(state_mutex);

        Serial.printf("[buton] apăsare %s acceptată la %lu ms%s\n", src, (unsigned long)press.t_ms,
                      already ? " (cererea era deja înregistrată)" : ": cerere înregistrată");
    }
}

// ---------------------------------------------------------------------------
// TODO 3: RUTINA DE ÎNTRERUPERE a butonului BOOT
// ---------------------------------------------------------------------------
void IRAM_ATTR button_isr(void) {
    ButtonPress press;
    press.t_ms = ticks_to_ms(xTaskGetTickCountFromISR());
    press.source = SRC_BOOT;
    BaseType_t woken = pdFALSE;
    xQueueSendFromISR(button_queue, &press, &woken);  // coada plină: apăsarea se pierde
    portYIELD_FROM_ISR(woken);
}

// Apelată de clientul MQTT pentru fiecare comandă primită (vezi connection.h).
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);
    if (strcmp(text, "BUTTON") == 0) {
        ButtonPress press;
        press.t_ms = ticks_to_ms(xTaskGetTickCount());
        press.source = SRC_DASHBOARD;
        if (xQueueSend(button_queue, &press, 0) != pdTRUE) {
            Serial.println("[mqtt] coada butonului este plină, BUTTON ignorat");
        }
    }
    // celelalte comenzi se ignoră
}

void setup() {
    Serial.begin(115200);
    // Fără așteptare la scriere: cu placa la USB, dar fără Serial Monitor deschis,
    // fiecare Serial.printf() ar aștepta până la 2 s să fie citit și ar întârzia
    // taskul care scrie. Așa, textul pe care nu-l citește nimeni se pierde.
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);  // așteptăm Serial Monitor, cel mult 3 s
    delay(200);
    Serial.println();
    Serial.printf("=== tema 03: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH);  // LED stins
    pinMode(BUTTON_PIN, INPUT_PULLUP);

    wifi_connect();
    time_sync();

    // TODO 0 (a): mutexul stării și coada butonului, înainte de mqtt_start()
    state_mutex = xSemaphoreCreateMutex();
    button_queue = xQueueCreate(BUTTON_QUEUE_LEN, sizeof(ButtonPress));
    if (state_mutex == nullptr || button_queue == nullptr) {
        Serial.println("EROARE: nu s-au putut crea mutexul sau coada");
        for (;;) delay(1000);
    }
    state.phase = CAR_GREEN;
    state.phase_start_ms = millis();
    state.ped_request = false;

    mqtt_start();

    // TODO 0 (b): cele trei taskuri, apoi întreruperea butonului
    xTaskCreate(control_task, "control", TASK_STACK, nullptr, PRIO_CONTROL, nullptr);
    xTaskCreate(output_task, "output", TASK_STACK, nullptr, PRIO_OUTPUT, nullptr);
    xTaskCreate(button_task, "button", TASK_STACK, nullptr, PRIO_SPORADIC, nullptr);
    attachInterrupt(digitalPinToInterrupt(BUTTON_PIN), button_isr, FALLING);
    Serial.println("taskurile au pornit, semaforul este pe CAR_GREEN");
}

void loop() {
    // Totul se face în taskuri: loop() nu are nimic de făcut.
    delay(1000);
}

// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.
//
// Aceeași logică și același protocol ca device-simulator din tema 01, cu trei
// taskuri FreeRTOS în loc de trei thread-uri Java:
//
//   task periodic --(coada de alarme)--> task sporadic
//        |                                     |
//        +---- telemetry ---> MQTT <--- alarm -+
//                              |
//                          commands --> command_received() --(coada de comenzi)--> task aperiodic ---> status
//
// Gata implementate (nu trebuie modificate):
//   config.h         setările: Wi-Fi, broker, STUDENT, valorile inițiale; DEVICE_ID
//                    se citește din adresa MAC a plăcii
//   connection.h/cpp Wi-Fi, ora exactă (SNTP), clientul MQTT, mqtt_publish(), epoch_ms()
//
// Schița se conectează la Wi-Fi și la broker, apoi rulează taskurile FreeRTOS
// pentru telemetrie, alarme și comenzi. Exemplele de pornire sunt în laboratorul
// 0 din rt-labs-esp32c3; vezi exemple/README.md din folderul temei.
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include "config.h"
#include "connection.h"

struct AlarmEvent {
    float temperature;
    float threshold;
};

struct QueuedCommand {
    char text[COMMAND_MAX_LEN];
    bool truncated;
};

static QueueHandle_t g_alarm_queue = nullptr;
static QueueHandle_t g_command_queue = nullptr;
static portMUX_TYPE g_settings_mux = portMUX_INITIALIZER_UNLOCKED;
static volatile uint32_t g_period_ms = PERIOD_MS_INITIAL;
static volatile float g_threshold = THRESHOLD_INITIAL;

static bool period_to_ticks(uint32_t period_ms, TickType_t* ticks) {
    if (period_ms == 0) {
        return false;
    }

    uint64_t period_ticks = ((uint64_t)period_ms * configTICK_RATE_HZ + 999) / 1000;
    if (period_ticks == 0) {
        period_ticks = 1;
    }
    if (period_ticks > portMAX_DELAY) {
        return false;
    }
    *ticks = (TickType_t)period_ticks;
    return true;
}

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
// Este temperatura siliciului, nu a camerei: imediat după pornirea Wi-Fi-ului
// poate fi în jur de 50 °C, apoi scade în câteva minute spre 35-40 °C.
// Rezoluția este de aproximativ 1 °C. Ca să provocați alarme, puneți pragul
// cu SET_THRESHOLD cu un grad sub valoarea citită.
float read_temperature(void) {
    return temperatureRead();
}

static void periodic_task(void* parameter) {
    (void)parameter;
    TickType_t last_wake = xTaskGetTickCount();

    for (;;) {
        uint32_t period_ms;
        float threshold;
        portENTER_CRITICAL(&g_settings_mux);
        period_ms = g_period_ms;
        threshold = g_threshold;
        portEXIT_CRITICAL(&g_settings_mux);

        float temperature = read_temperature();
        char message[96];
        snprintf(message, sizeof(message), "%s;%lld;%.2f",
                 DEVICE_ID, (long long)epoch_ms(), temperature);
        mqtt_publish("telemetry", message);

        if (temperature > threshold) {
            AlarmEvent event{temperature, threshold};
            if (xQueueSend(g_alarm_queue, &event, 0) != pdTRUE) {
                Serial.println("[periodic] coada de alarme plina; eveniment respins");
            }
        }

        TickType_t period_ticks;
        if (!period_to_ticks(period_ms, &period_ticks)) {
            period_ticks = 1;
        }
        // Absolut: timpul de execuție al jobului nu se adună la perioada T.
        xTaskDelayUntil(&last_wake, period_ticks);
    }
}

static void sporadic_task(void* parameter) {
    (void)parameter;
    uint32_t last_alarm_ms = 0;
    bool has_published_alarm = false;

    for (;;) {
        AlarmEvent event;
        if (xQueueReceive(g_alarm_queue, &event, portMAX_DELAY) != pdTRUE) {
            continue;
        }

        uint32_t now = millis();
        uint32_t elapsed = now - last_alarm_ms;
        if (has_published_alarm && elapsed < MIN_INTERARRIVAL_MS) {
            Serial.printf("[sporadic] alarmă respinsă: au trecut %lu ms (minim %lu ms)\n",
                          (unsigned long)elapsed, (unsigned long)MIN_INTERARRIVAL_MS);
            continue;
        }

        char message[112];
        snprintf(message, sizeof(message), "%s;%lld;%.2f;%.2f",
                 DEVICE_ID, (long long)epoch_ms(), event.temperature, event.threshold);
        mqtt_publish("alarm", message);
        last_alarm_ms = now;
        has_published_alarm = true;
    }
}

static bool parse_period(const char* text, uint32_t* period_ms) {
    if (text == nullptr || *text == '\0' || *text == '-') {
        return false;
    }
    char* end = nullptr;
    unsigned long value = strtoul(text, &end, 10);
    if (end == text || *end != '\0' || value == 0 || value > UINT32_MAX) {
        return false;
    }
    TickType_t ticks;
    if (!period_to_ticks((uint32_t)value, &ticks)) {
        return false;
    }
    *period_ms = (uint32_t)value;
    return true;
}

static bool parse_threshold(const char* text, float* threshold) {
    if (text == nullptr || *text == '\0') {
        return false;
    }
    char* end = nullptr;
    float value = strtof(text, &end);
    if (end == text || *end != '\0' || !isfinite(value)) {
        return false;
    }
    *threshold = value;
    return true;
}

static void execute_command(const QueuedCommand& command, char* response, size_t response_size) {
    if (command.truncated) {
        snprintf(response, response_size, "ERROR command too long");
        return;
    }

    char text[COMMAND_MAX_LEN];
    memcpy(text, command.text, sizeof(text));
    char* parts[3] = {};
    size_t count = 0;
    char* save_ptr = nullptr;
    for (char* token = strtok_r(text, " \t\r\n", &save_ptr);
         token != nullptr;
         token = strtok_r(nullptr, " \t\r\n", &save_ptr)) {
        if (count == 3) {
            snprintf(response, response_size, "ERROR too many arguments");
            return;
        }
        parts[count++] = token;
    }
    if (count == 0) {
        snprintf(response, response_size, "ERROR empty command");
        return;
    }

    if (strcmp(parts[0], "SET_INTERVAL") == 0) {
        uint32_t period_ms;
        if (count != 2 || !parse_period(count == 2 ? parts[1] : nullptr, &period_ms)) {
            snprintf(response, response_size, "ERROR invalid interval");
            return;
        }
        portENTER_CRITICAL(&g_settings_mux);
        g_period_ms = period_ms;
        portEXIT_CRITICAL(&g_settings_mux);
        snprintf(response, response_size, "OK SET_INTERVAL %lu", (unsigned long)period_ms);
    } else if (strcmp(parts[0], "SET_THRESHOLD") == 0) {
        float threshold;
        if (count != 2 || !parse_threshold(count == 2 ? parts[1] : nullptr, &threshold)) {
            snprintf(response, response_size, "ERROR invalid threshold");
            return;
        }
        portENTER_CRITICAL(&g_settings_mux);
        g_threshold = threshold;
        portEXIT_CRITICAL(&g_settings_mux);
        snprintf(response, response_size, "OK SET_THRESHOLD %.2f", threshold);
    } else if (strcmp(parts[0], "RESET") == 0 && count == 1) {
        portENTER_CRITICAL(&g_settings_mux);
        g_period_ms = PERIOD_MS_INITIAL;
        g_threshold = THRESHOLD_INITIAL;
        portEXIT_CRITICAL(&g_settings_mux);
        snprintf(response, response_size, "OK RESET");
    } else if (strcmp(parts[0], "STATUS") == 0 && count == 1) {
        uint32_t period_ms;
        float threshold;
        portENTER_CRITICAL(&g_settings_mux);
        period_ms = g_period_ms;
        threshold = g_threshold;
        portEXIT_CRITICAL(&g_settings_mux);
        snprintf(response, response_size, "OK STATUS interval=%lu threshold=%.2f",
                 (unsigned long)period_ms, threshold);
    } else if (strcmp(parts[0], "RESET") == 0 || strcmp(parts[0], "STATUS") == 0) {
        snprintf(response, response_size, "ERROR wrong number of arguments");
    } else {
        snprintf(response, response_size, "ERROR unknown command");
    }
}

static void aperiodic_task(void* parameter) {
    (void)parameter;
    for (;;) {
        QueuedCommand command;
        if (xQueueReceive(g_command_queue, &command, portMAX_DELAY) != pdTRUE) {
            continue;
        }

        char response[COMMAND_MAX_LEN + 48];
        execute_command(command, response, sizeof(response));
        char message[COMMAND_MAX_LEN + 96];
        snprintf(message, sizeof(message), "%s;%lld;%s",
                 DEVICE_ID, (long long)epoch_ms(), response);
        mqtt_publish("status", message);
        Serial.printf("[aperiodic] %s\n", response);
    }
}

// Apelată de clientul MQTT pentru fiecare comandă primită (vezi connection.h).
void command_received(const char* text) {
    if (g_command_queue == nullptr || text == nullptr) {
        Serial.println("[mqtt] coada de comenzi indisponibilă");
        return;
    }
    QueuedCommand command{};
    size_t length = strnlen(text, COMMAND_MAX_LEN);
    command.truncated = length == COMMAND_MAX_LEN;
    if (command.truncated) {
        length = COMMAND_MAX_LEN - 1;
    }
    memcpy(command.text, text, length);
    command.text[length] = '\0';

    if (xQueueSend(g_command_queue, &command, 0) != pdTRUE) {
        Serial.println("[mqtt] coada de comenzi plina; comanda respinsa");
    }
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
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    wifi_connect();
    time_sync();

    g_alarm_queue = xQueueCreate(8, sizeof(AlarmEvent));
    g_command_queue = xQueueCreate(8, sizeof(QueuedCommand));
    if (g_alarm_queue == nullptr || g_command_queue == nullptr) {
        Serial.println("eroare: nu s-au putut crea cozile FreeRTOS");
        for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
    }

    mqtt_start();

    BaseType_t periodic_ok = xTaskCreate(periodic_task, "periodic", 4096, nullptr, 4, nullptr);
    BaseType_t sporadic_ok = xTaskCreate(sporadic_task, "sporadic", 4096, nullptr, 3, nullptr);
    BaseType_t aperiodic_ok = xTaskCreate(aperiodic_task, "aperiodic", 4096, nullptr, 2, nullptr);
    if (periodic_ok != pdPASS || sporadic_ok != pdPASS || aperiodic_ok != pdPASS) {
        Serial.println("eroare: nu s-au putut porni toate taskurile FreeRTOS");
        for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

void loop() {
    // Totul se face în taskuri: loop() nu are nimic de făcut.
    delay(1000);
}

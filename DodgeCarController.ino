// Código para ESP32 usando Arduino IDE

#include <BluetoothSerial.h>

BluetoothSerial SerialBT;

// Pines conectados a los motores
const int motorPin1 = 5;  // Motor izquierdo adelante
const int motorPin2 = 18; // Motor izquierdo atrás
const int motorPin3 = 19; // Motor derecho adelante
const int motorPin4 = 21; // Motor derecho atrás

// Variable para rastrear el estado de conexión
bool isConnected = false;

// Callback cuando un dispositivo se conecta
void onConnect() {
  Serial.println("Dispositivo conectado");
  isConnected = true;
}

// Callback cuando un dispositivo se desconecta
void onDisconnect() {
  Serial.println("Dispositivo desconectado");
  isConnected = false;
}

void setup() {
  Serial.begin(115200);
  while (!Serial) {
    ; // Espera a que se inicie el monitor serial
  }

  Serial.println("Inicializando Bluetooth...");
  if (!SerialBT.begin("CarController")) { // Nombre del dispositivo Bluetooth
    Serial.println("Error al iniciar Bluetooth");
  } else {
    Serial.println("Bluetooth iniciado correctamente");
  }

  // Configurar pines como salidas
  pinMode(motorPin1, OUTPUT);
  pinMode(motorPin2, OUTPUT);
  pinMode(motorPin3, OUTPUT);
  pinMode(motorPin4, OUTPUT);

  // Asociar callbacks
  SerialBT.register_callback([](esp_spp_cb_event_t event, esp_spp_cb_param_t *param){
    if (event == ESP_SPP_SRV_OPEN_EVT) {
      onConnect();
    } else if (event == ESP_SPP_CLOSE_EVT) {
      onDisconnect();
    }
  });

  Serial.println("Listo para recibir comandos Bluetooth.");
}

void loop() {
  if (isConnected && SerialBT.available()) {
    char command = SerialBT.read();
    Serial.print("Comando recibido: ");
    Serial.println(command);
    executeCommand(command);
  }
}

void executeCommand(char cmd) {
  switch(cmd) {
    case 'f': // Adelante
      adelante();
      Serial.println("Movimiento: Adelante");
      break;
    case 'b': // Atrás
      atras();
      Serial.println("Movimiento: Atrás");
      break;
    case 'l': // Izquierda
      izquierda();
      Serial.println("Movimiento: Izquierda");
      break;
    case 'r': // Derecha
      derecha();
      Serial.println("Movimiento: Derecha");
      break;
    case 's': // Detener
      detener();
      Serial.println("Movimiento: Detener");
      break;
    default:
      detener();
      Serial.println("Comando desconocido. Deteniendo.");
      break;
  }
}

void adelante() {
  digitalWrite(motorPin1, HIGH);
  digitalWrite(motorPin2, LOW);
  digitalWrite(motorPin3, HIGH);
  digitalWrite(motorPin4, LOW);
}

void atras() {
  digitalWrite(motorPin1, LOW);
  digitalWrite(motorPin2, HIGH);
  digitalWrite(motorPin3, LOW);
  digitalWrite(motorPin4, HIGH);
}

void izquierda() {
  digitalWrite(motorPin1, LOW);
  digitalWrite(motorPin2, LOW);
  digitalWrite(motorPin3, HIGH);
  digitalWrite(motorPin4, LOW);
}

void derecha() {
  digitalWrite(motorPin1, HIGH);
  digitalWrite(motorPin2, LOW);
  digitalWrite(motorPin3, LOW);
  digitalWrite(motorPin4, LOW);
}

void detener() {
  digitalWrite(motorPin1, LOW);
  digitalWrite(motorPin2, LOW);
  digitalWrite(motorPin3, LOW);
  digitalWrite(motorPin4, LOW);
}
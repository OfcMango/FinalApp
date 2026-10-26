/* HaptiBraille combined two-way prototype for original ESP32 / ESP32-WROOM.
   Phone -> display protocol: CELL:<0..63>\n
   Keypad -> phone protocol: KEY:<0..63>\n (send dot chord on release),
   SPACE\n, BACKSPACE\n.

   OUTPUTS (through MOSFET drivers only): dots 1..6 = GPIO 16,17,18,19,21,22.
   KEYS (switch from GPIO to GND; INPUT_PULLUP): dots 1..6 = 23,25,26,27,32,33;
   Space = GPIO 4; Backspace = GPIO 5.

   IMPORTANT: output and input pins are deliberately different. Never connect
   electromagnets directly to ESP32 pins. Use rated MOSFETs, gate resistors,
   pulldowns, flyback suppression, external coil supply, and common ground.
   Test outputs with LEDs before connecting actuators. GPIO 4/5 are strapping
   pins on some ESP32 modules; if boot problems occur, move Space/Backspace to
   other safe, unused GPIOs and update this sketch.
*/
#include "BluetoothSerial.h"
BluetoothSerial SerialBT;

const uint8_t DOT_OUT[6] = {16, 17, 18, 19, 21, 22};
const uint8_t DOT_KEY[6] = {23, 25, 26, 27, 32, 33};
const uint8_t SPACE_KEY = 4;
const uint8_t BACKSPACE_KEY = 5;

String rxLine;
bool chordWasDown = false;
uint32_t lastKeyEvent = 0;
const uint32_t DEBOUNCE_MS = 35;

void showCell(uint8_t mask) {
  for (int i = 0; i < 6; ++i) digitalWrite(DOT_OUT[i], (mask & (1U << i)) ? HIGH : LOW);
}

uint8_t readChord() {
  uint8_t mask = 0;
  for (int i = 0; i < 6; ++i) if (digitalRead(DOT_KEY[i]) == LOW) mask |= (1U << i);
  return mask;
}

void handlePhoneLine(String line) {
  line.trim();
  if (!line.startsWith("CELL:")) return;
  int mask = line.substring(5).toInt();
  if (mask >= 0 && mask <= 63) {
    showCell((uint8_t)mask);
    Serial.printf("CELL:%d\n", mask);
  }
}

void setup() {
  Serial.begin(115200);
  for (int i = 0; i < 6; ++i) {
    pinMode(DOT_OUT[i], OUTPUT);
    digitalWrite(DOT_OUT[i], LOW);
    pinMode(DOT_KEY[i], INPUT_PULLUP);
  }
  pinMode(SPACE_KEY, INPUT_PULLUP);
  pinMode(BACKSPACE_KEY, INPUT_PULLUP);
  if (!SerialBT.begin("HaptiBraille")) Serial.println("Bluetooth Classic failed; use a compatible original ESP32 board.");
  else Serial.println("HaptiBraille ready. Pair phone with HaptiBraille.");
}

void loop() {
  while (SerialBT.available()) {
    char c = (char)SerialBT.read();
    if (c == '\n') { handlePhoneLine(rxLine); rxLine = ""; }
    else if (c != '\r' && rxLine.length() < 32) rxLine += c;
    else if (rxLine.length() >= 32) rxLine = "";
  }

  uint32_t now = millis();
  uint8_t chord = readChord();
  bool spaceDown = digitalRead(SPACE_KEY) == LOW;
  bool backDown = digitalRead(BACKSPACE_KEY) == LOW;
  bool anyDown = chord != 0 || spaceDown || backDown;
  if (anyDown && !chordWasDown && now - lastKeyEvent >= DEBOUNCE_MS) {
    // Give the user a short moment to form a multi-key Braille chord.
    delay(100);
    chord = readChord();
    spaceDown = digitalRead(SPACE_KEY) == LOW;
    backDown = digitalRead(BACKSPACE_KEY) == LOW;
    if (spaceDown) SerialBT.println("SPACE");
    else if (backDown) SerialBT.println("BACKSPACE");
    else if (chord != 0) { SerialBT.printf("KEY:%u\n", chord); }
    lastKeyEvent = millis();
  }
  chordWasDown = anyDown;
  delay(2);
}

# HaptiBraille Communicator — combined prototype

This single Android app combines both project directions:

1. **Speech → Braille:** speak a normal sentence; Android speech recognition returns text, the app converts it to basic English Grade 1 six-dot patterns, and sends `CELL:<mask>\n` to the ESP32. The ESP32 activates the matching six output pins.
2. **Braille keypad → speech/text:** the ESP32 sends `KEY:<mask>\n`, `SPACE\n`, or `BACKSPACE\n` to the phone. The app decodes each basic letter, shows accumulated text, and the **Speak received text** button reads it aloud.

## Build the APK with GitHub Actions
1. Extract this ZIP.
2. Create a GitHub repository and upload the *contents* of the `SpeechBraille` folder to the repository root. Keep `.github/workflows/build.yml` at exactly that path.
3. Open **Actions** and run **Build HaptiBraille APK**.
4. Download `HaptiBraille-combined-apk` from the successful run's Artifacts section and extract `app-debug.apk`.

## ESP32 hardware and firmware
- Firmware: `esp32_firmware/SpeechBrailleESP32.ino` (same as `HaptiBrailleCombined.ino`).
- Intended board: original ESP32 / ESP32-WROOM with Bluetooth Classic support. ESP32-C3/S3 do not support Bluetooth Classic SPP; they need a BLE-based app/firmware variant.
- Pair the phone with the Bluetooth device named **HaptiBraille** before connecting in the app.

### Pin map

| Function | GPIOs |
|---|---|
| MOSFET driver inputs for Braille dots 1–6 | 16, 17, 18, 19, 21, 22 |
| Braille dot keys 1–6 (switch to GND, `INPUT_PULLUP`) | 23, 25, 26, 27, 32, 33 |
| Space key | 4 |
| Backspace key | 5 |

Input and output pins are deliberately separate. If your current keypad is wired to GPIO 16,17,18,19,21,22, it conflicts with the six output pins; rewire the keypad to the listed input pins or choose a new non-conflicting map before connecting hardware. GPIO 4 and 5 are strapping pins on some ESP32 boards; if they cause boot problems, move Space/Backspace to safe unused pins and update the sketch.

## Critical safety and limitations
- Never connect electromagnets/solenoids directly to ESP32 GPIO. Use one suitably rated MOSFET driver per coil, gate resistor/pulldown, flyback suppression, external coil power supply sized for current, and common ground.
- Test output pins using LEDs and suitable resistors first. The code's HIGH/LOW logic assumes active-HIGH driver inputs; invert if your driver module is active-low.
- Speech recognition uses Android's configured recognition service and may require internet. Continuous listening is simulated by restarting recognition after each phrase; it is not true word-by-word streaming.
- Braille support is basic English Grade 1. Capital indicators and full contracted Braille are not implemented; digits are encoded with a number sign for the outgoing display, while keypad input currently decodes letters/basic punctuation only.
- Keypad-to-text firmware assumes each Braille cell is entered as a chord and sends the mask when the chord is pressed. The simple prototype does not yet include a dedicated confirm key; test chord timing with LEDs/serial monitor and adjust if needed.
- This project has been assembled from the provided sources but has not been compiled or tested on your specific phone/ESP32 in this session. Verify the GitHub Actions build and test with LEDs before actuators.

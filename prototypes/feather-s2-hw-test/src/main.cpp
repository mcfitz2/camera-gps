// Hardware smoke test for Adafruit Feather ESP32-S2 Reverse TFT.
// Shows button state on the TFT, cycles the NeoPixel, and logs to serial.
#include <Arduino.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ST7789.h>
#include <Adafruit_NeoPixel.h>

Adafruit_ST7789 tft(TFT_CS, TFT_DC, TFT_RST);
Adafruit_NeoPixel pixel(1, PIN_NEOPIXEL, NEO_GRB + NEO_KHZ800);

// D0 is active-low (BOOT button); D1/D2 are active-high.
const int BTN_D0 = 0;
const int BTN_D1 = 1;
const int BTN_D2 = 2;

void setup() {
  Serial.begin(115200);

  pinMode(TFT_I2C_POWER, OUTPUT);
  digitalWrite(TFT_I2C_POWER, HIGH);
  pinMode(TFT_BACKLITE, OUTPUT);
  digitalWrite(TFT_BACKLITE, HIGH);
  delay(10);

  tft.init(135, 240);
  tft.setRotation(3);
  tft.fillScreen(ST77XX_BLACK);

  pinMode(NEOPIXEL_POWER, OUTPUT);
  digitalWrite(NEOPIXEL_POWER, HIGH);
  pixel.begin();
  pixel.setBrightness(30);

  pinMode(BTN_D0, INPUT_PULLUP);
  pinMode(BTN_D1, INPUT_PULLDOWN);
  pinMode(BTN_D2, INPUT_PULLDOWN);
}

void drawButton(int y, const char *label, bool pressed) {
  tft.fillRect(0, y, 240, 24, pressed ? ST77XX_GREEN : ST77XX_BLACK);
  tft.setCursor(8, y + 4);
  tft.setTextColor(pressed ? ST77XX_BLACK : ST77XX_WHITE);
  tft.print(label);
  tft.print(pressed ? " PRESSED" : " -");
}

void loop() {
  static uint32_t lastDraw = 0;
  static uint8_t hue = 0;

  bool d0 = digitalRead(BTN_D0) == LOW;
  bool d1 = digitalRead(BTN_D1) == HIGH;
  bool d2 = digitalRead(BTN_D2) == HIGH;

  if (millis() - lastDraw > 100) {
    lastDraw = millis();
    tft.setTextSize(2);
    tft.setCursor(8, 6);
    tft.setTextColor(ST77XX_YELLOW, ST77XX_BLACK);
    tft.print("HW TEST  ");
    tft.print(millis() / 1000);
    tft.print("s");
    drawButton(36, "D0", d0);
    drawButton(64, "D1", d1);
    drawButton(92, "D2", d2);

    pixel.setPixelColor(0, pixel.ColorHSV(hue++ * 256));
    pixel.show();

    Serial.printf("t=%lus D0=%d D1=%d D2=%d\n", millis() / 1000, d0, d1, d2);
  }
}

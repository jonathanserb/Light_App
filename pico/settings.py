"""Settings you can tune without reading the rest of the code.

Edit a value, copy this file to the Pico, and reboot it. Nothing here is
changed by the phone app; what the app changes lives in config.json.
"""

# ---- identity ------------------------------------------------------------
DEFAULT_NAME = "Pico"          # Bluetooth name until you rename it from the app
CONFIG_FILE = "config.json"    # where pins, schedules and parameters are saved

# ---- pins ----------------------------------------------------------------
# GP numbers the app is allowed to use. GP23-25 and GP29 are wired to the
# wireless chip on a Pico W, so they are left out. Remove any you want protected.
USABLE_PINS = list(range(0, 23)) + [26, 27, 28]
DEFAULT_PWM_FREQ = 1000        # Hz, for dimmable pins (per pin: "freq" in pin.cfg)
ADC_VREF = 3.3                 # volts at a full-scale analog reading

# ---- schedules and saving ------------------------------------------------
MAX_SCHEDULES = 32
SAVE_DELAY_S = 2               # flash is written this long after the last change
CLOCK_VALID_FROM_YEAR = 2024   # an earlier year means "clock not set yet"

# ---- bluetooth -----------------------------------------------------------
ADVERTISE_INTERVAL_MS = 250    # lower = found faster, higher = less power
PREFERRED_MTU = 247            # bytes per packet the Pico offers the phone
MAX_LINE_BYTES = 2048          # longest command line accepted

# ---- main loop -----------------------------------------------------------
LOOP_SLEEP_MS = 20             # pause between checks for incoming commands
RESTART_AFTER_CRASH_S = 5      # wait this long, then reboot, if main.py crashes

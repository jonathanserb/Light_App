"""Real hardware access for the Raspberry Pi Pico W / Pico 2 W (MicroPython)."""
import gc
import time

import machine

import settings
from machine import ADC, PWM, Pin, RTC

_PULLS = {"in": None, "in_up": Pin.PULL_UP, "in_down": Pin.PULL_DOWN}


class Hal:
    def __init__(self):
        self._pins = {}
        self._pwms = {}
        self._adcs = {}
        self._last_ms = time.ticks_ms()
        self._uptime_ms = 0

    def valid_pin(self, n):
        return n in settings.USABLE_PINS

    def _id(self, key):
        return "LED" if key == "LED" else int(key)

    def _stop_pwm(self, key):
        pwm = self._pwms.pop(key, None)
        if pwm:
            pwm.deinit()

    def pin_setup(self, key, mode):
        self._stop_pwm(key)
        if mode == "out":
            self._pins[key] = Pin(self._id(key), Pin.OUT)
        else:
            self._pins[key] = Pin(self._id(key), Pin.IN, _PULLS[mode])

    def pin_write(self, key, value):
        self._pins[key].value(value)

    def pin_read(self, key):
        return self._pins[key].value()

    def pwm_write(self, key, duty, freq):
        pwm = self._pwms.get(key)
        if pwm is None:
            pwm = PWM(Pin(self._id(key)))  # raises for "LED": it isn't a real GPIO
            self._pwms[key] = pwm
            self._pins.pop(key, None)
        pwm.freq(int(freq))
        pwm.duty_u16(int(duty * 65535 / 100))

    def adc_read(self, ch):
        if ch not in self._adcs:
            self._adcs[ch] = ADC(ch)
        return self._adcs[ch].read_u16()

    def temp_c(self):
        volts = self.adc_read(4) * settings.ADC_VREF / 65535
        return 27 - (volts - 0.706) / 0.001721

    def now(self):
        t = time.localtime()
        return (t[0], t[1], t[2], t[3], t[4], t[5], t[6])

    def set_time(self, t):
        # RTC order is (year, month, day, weekday, hour, minute, second, subsec)
        RTC().datetime((t[0], t[1], t[2], t[6], t[3], t[4], t[5], 0))

    def ticks(self):
        """Seconds since boot. Unlike time.time(), unaffected by setting the clock."""
        ms = time.ticks_ms()
        self._uptime_ms += time.ticks_diff(ms, self._last_ms)
        self._last_ms = ms
        return self._uptime_ms / 1000

    def mem_free(self):
        gc.collect()
        return gc.mem_free()

    def reset(self):
        machine.reset()

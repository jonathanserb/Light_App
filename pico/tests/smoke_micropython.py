"""Smoke test under the real MicroPython interpreter (unix port), to catch
anything that works in desktop Python but not on the board.
Run from the pico/ folder:  micropython tests/smoke_micropython.py
"""
import sys
sys.path.insert(0, ".")
import asyncio
import json
import os

from controller import Controller

PATH = "smoke_config.json"


class Hal:
    def __init__(self):
        self.t = [2026, 11, 16, 18, 29, 0, 0]
        self.clock = 0
        self.levels = {}

    def valid_pin(self, n): return 0 <= n <= 22
    def pin_setup(self, key, mode): pass
    def pin_write(self, key, value): self.levels[key] = value
    def pin_read(self, key): return 0
    def pwm_write(self, key, duty, freq): self.levels[key] = duty
    def adc_read(self, ch): return 1000
    def temp_c(self): return 25.0
    def now(self): return tuple(self.t)
    def set_time(self, t): self.t = list(t)
    def ticks(self): return self.clock
    def mem_free(self): return 1
    def reset(self): pass


async def main():
    hal = Hal()
    ctl = Controller(hal, PATH)
    events = []
    ctl.emit = events.append

    def ok(**req):
        res = json.loads(ctl.handle_line(json.dumps(req).encode()))
        assert res["ok"], res
        return res

    for bad in (b"junk", b"\xff", b'{"cmd":"pin.set","pin":"x"}', b'{"cmd":"sched.put","item":3}'):
        assert not json.loads(ctl.handle_line(bad))["ok"]
    ok(cmd="info")
    ok(cmd="time.set", t=[2026, 11, 16, 18, 29, 0, 0])
    ok(cmd="pin.cfg", pin=2, mode="in_up", label="door")
    ok(cmd="pin.set", pin="LED", value=1)
    ok(cmd="pin.list")
    ok(cmd="sched.put", item={"h": 18, "m": 30, "dur": 5,
                              "act": {"type": "pin", "pin": 15, "value": 1}})
    hal.t[4] = 30
    ctl.tick()
    assert hal.levels["15"] == 1 and events[0]["ev"] == "fired", events
    hal.clock = 6
    ctl.tick()
    assert hal.levels["15"] == 0
    assert os.stat(PATH)[6] > 0  # config was saved

    ok(cmd="param.set", key="blink.on_ms", value=1)
    ok(cmd="prog.run", name="blink", args={"pin": 3, "off_ms": 1})
    await asyncio.sleep(0.05)
    assert ok(cmd="info")["prog"] == "blink" and "3" in hal.levels
    ok(cmd="prog.stop")
    ok(cmd="prog.run", name="timer", args={"pin": 4, "seconds": 0.01})
    await asyncio.sleep(0.05)
    assert ctl.prog_name is None and {"ev": "prog_end", "name": "timer"} in events

    ctl2 = Controller(Hal(), PATH)  # "reboot": state comes back from flash
    assert len(ctl2.sched) == 1 and ctl2.pins["2"]["label"] == "door"
    os.remove(PATH)
    print("micropython smoke test OK")


asyncio.run(main())

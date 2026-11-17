"""Desktop tests for the firmware logic. Run: python3 -m unittest discover pico/tests"""
import asyncio
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from controller import Controller  # noqa: E402


class FakeHal:
    def __init__(self):
        self.t = [2000, 1, 1, 0, 0, 0, 5]
        self.clock = 0.0
        self.modes = {}
        self.levels = {}
        self.pwm = {}
        self.inputs = {}
        self.was_reset = False

    def valid_pin(self, n):
        return 0 <= n <= 22 or 26 <= n <= 28

    def pin_setup(self, key, mode):
        self.modes[key] = mode
        self.pwm.pop(key, None)

    def pin_write(self, key, value):
        self.levels[key] = value

    def pin_read(self, key):
        return self.inputs.get(key, 0)

    def pwm_write(self, key, duty, freq):
        if key == "LED":
            raise ValueError("no pwm")
        self.modes[key] = "pwm"
        self.pwm[key] = (duty, freq)

    def adc_read(self, ch):
        return 32768

    def temp_c(self):
        return 25.04

    def now(self):
        return tuple(self.t)

    def set_time(self, t):
        self.t = list(t)

    def ticks(self):
        return self.clock

    def mem_free(self):
        return 100000

    def reset(self):
        self.was_reset = True


class Base(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.dir.name, "config.json")
        self.hal = FakeHal()
        self.events = []
        self.ctl = self.make()
        self.n = 0

    def tearDown(self):
        self.dir.cleanup()

    def make(self):
        ctl = Controller(self.hal, self.path)
        ctl.emit = self.events.append
        return ctl

    def cmd(self, cmd, **kw):
        self.n += 1
        res = json.loads(self.ctl.handle_line(json.dumps(dict(kw, id=self.n, cmd=cmd))))
        self.assertEqual(res["id"], self.n)
        return res

    def ok(self, cmd, **kw):
        res = self.cmd(cmd, **kw)
        self.assertTrue(res["ok"], res)
        return res

    def advance(self, seconds):
        self.hal.clock += seconds
        self.ctl.tick()

    def set_clock(self, h, m, wd=0):
        self.ok("time.set", t=[2026, 11, 16, h, m, 0, wd])


class TestCommands(Base):
    def test_bad_input_never_raises(self):
        for line in ("garbage", "[]", "{}", '{"id":1,"cmd":"nope"}', b"\xff\xfe",
                     '{"id":1,"cmd":"pin.set"}', '{"id":1,"cmd":"pin.set","pin":99,"value":1}',
                     '{"id":1,"cmd":"sched.put","item":{}}', '{"id":1,"cmd":"time.set","t":[1]}'):
            self.assertFalse(json.loads(self.ctl.handle_line(line))["ok"], line)

    def test_info_and_time(self):
        self.assertFalse(self.ok("info")["time_valid"])
        self.set_clock(18, 30)
        info = self.ok("info")
        self.assertTrue(info["time_valid"])
        self.assertEqual(info["time"], [2026, 11, 16, 18, 30, 0, 0])

    def test_pins(self):
        self.ok("pin.set", pin=15, value=1)
        self.assertEqual((self.hal.modes["15"], self.hal.levels["15"]), ("out", 1))
        self.ok("pin.set", pin="LED", value=1)
        self.ok("pin.set", pin=16, duty=40)
        self.assertEqual(self.hal.pwm["16"], (40, 1000))
        self.ok("pin.cfg", pin=2, mode="in_up", label="door")
        self.hal.inputs["2"] = 1
        self.assertEqual(self.ok("pin.get", pin=2)["value"], 1)
        pins = {p["pin"]: p for p in self.ok("pin.list")["pins"]}
        self.assertEqual(pins["2"]["label"], "door")
        self.assertEqual(pins["16"]["mode"], "pwm")
        self.ok("pin.cfg", pin=2, mode="off")
        self.assertNotIn("2", self.ctl.pins)

    def test_unsupported_mode_is_rolled_back(self):
        self.assertFalse(self.cmd("pin.cfg", pin="LED", mode="pwm")["ok"])
        self.assertNotIn("LED", self.ctl.pins)
        self.ok("pin.set", pin="LED", value=1)
        self.assertFalse(self.cmd("pin.cfg", pin="LED", mode="pwm")["ok"])
        self.assertEqual(self.ctl.pins["LED"]["mode"], "out")

    def test_failed_dim_leaves_pin_as_it_was(self):
        self.assertFalse(self.cmd("pin.set", pin="LED", duty=50)["ok"])
        self.assertNotIn("LED", self.ctl.pins)
        self.ok("pin.set", pin="LED", value=1)
        self.assertFalse(self.cmd("pin.set", pin="LED", duty=50)["ok"])
        self.assertEqual((self.ctl.pins["LED"]["mode"], self.hal.levels["LED"]), ("out", 1))

    def test_old_config_gets_new_pin_fields(self):
        with open(self.path, "w") as f:
            json.dump({"pins": {"7": {"mode": "out", "value": 1}}}, f)
        self.ctl = self.make()
        self.assertEqual(self.hal.levels["7"], 1)
        self.assertEqual(self.ok("pin.list")["pins"][0]["label"], "")

    def test_wireless_pins_rejected(self):
        self.assertFalse(self.cmd("pin.set", pin=25, value=1)["ok"])

    def test_passcode(self):
        self.ok("pass.set", **{"pass": "1234"})
        self.ctl.on_disconnect()
        self.assertTrue(self.ok("info")["locked"])
        self.assertEqual(self.cmd("pin.set", pin=1, value=1)["err"], "locked")
        self.assertFalse(self.cmd("auth", **{"pass": "0000"})["ok"])
        self.ok("auth", **{"pass": "1234"})
        self.ok("pin.set", pin=1, value=1)

    def test_persistence_across_reboot(self):
        self.ok("pin.set", pin=15, value=1)
        self.ok("name.set", name="Porch")
        self.ok("sched.put", item={"h": 7, "m": 0, "act": {"type": "pin", "pin": 15, "value": 0}})
        self.assertFalse(os.path.exists(self.path))  # writes are batched
        self.advance(3)
        self.hal = FakeHal()
        self.ctl = self.make()
        self.assertEqual(self.hal.levels["15"], 1)
        self.assertEqual(self.ok("info")["name"], "Porch")
        self.assertEqual(len(self.ok("sched.list")["items"]), 1)

    def test_reboot(self):
        self.ok("reboot")
        self.assertFalse(self.hal.was_reset)
        self.advance(2)
        self.assertTrue(self.hal.was_reset)


class TestSchedules(Base):
    def put(self, **item):
        return self.ok("sched.put", item=item)["item"]["id"]

    def test_fires_once_at_the_right_minute(self):
        self.put(name="on", h=18, m=30, act={"type": "pin", "pin": 15, "value": 1})
        self.set_clock(18, 29)
        self.advance(1)
        self.assertNotIn("15", self.hal.levels)
        self.hal.t[4] = 30
        self.advance(1)
        self.assertEqual(self.hal.levels["15"], 1)
        self.assertEqual(self.events, [{"ev": "fired", "sched": 1, "name": "on"}])
        self.advance(1)
        self.assertEqual(len(self.events), 1)

    def test_not_before_clock_is_set(self):
        self.put(h=0, m=0, act={"type": "pin", "pin": 15, "value": 1})
        self.advance(1)
        self.assertEqual(self.events, [])

    def test_days_and_disabled(self):
        self.put(h=8, m=0, days=0b0100000, act={"type": "pin", "pin": 1, "value": 1})  # Sat
        self.put(h=8, m=0, en=False, act={"type": "pin", "pin": 2, "value": 1})
        self.set_clock(8, 0, wd=0)
        self.advance(1)
        self.assertEqual(self.events, [])
        self.hal.t[2], self.hal.t[6] = 21, 5
        self.advance(1)
        self.assertEqual(self.hal.levels, {"1": 1})

    def test_duration_reverts(self):
        self.put(h=6, m=0, dur=90, act={"type": "pin", "pin": 15, "value": 1})
        self.put(h=6, m=0, dur=90, act={"type": "pwm", "pin": 16, "duty": 80})
        self.set_clock(6, 0)
        self.advance(1)
        self.advance(80)
        self.assertEqual((self.hal.levels["15"], self.hal.pwm["16"][0]), (1, 80))
        self.advance(10)
        self.assertEqual((self.hal.levels["15"], self.hal.pwm["16"][0]), (0, 0))

    def test_manual_set_cancels_pending_revert(self):
        self.put(h=6, m=0, dur=60, act={"type": "pin", "pin": 15, "value": 0})
        self.set_clock(6, 0)
        self.advance(1)
        self.ok("pin.set", pin=15, value=0)
        self.advance(100)
        self.assertEqual(self.hal.levels["15"], 0)

    def test_update_delete_and_limit(self):
        sid = self.put(h=1, m=1, act={"type": "stop"})
        self.ok("sched.put", item={"id": sid, "h": 2, "m": 2, "act": {"type": "stop"}})
        items = self.ok("sched.list")["items"]
        self.assertEqual([(s["id"], s["h"]) for s in items], [(sid, 2)])
        self.ok("sched.del", sid=sid)
        self.assertFalse(self.cmd("sched.del", sid=sid)["ok"])
        for _ in range(32):
            self.put(h=1, m=1, act={"type": "stop"})
        self.assertFalse(self.cmd("sched.put", item={"h": 1, "m": 1, "act": {"type": "stop"}})["ok"])


class TestPrograms(unittest.IsolatedAsyncioTestCase, Base):
    def setUp(self):
        Base.setUp(self)

    async def settle(self, seconds=0.03):
        await asyncio.sleep(seconds)

    async def test_run_stop_and_live_params(self):
        progs = {p["name"]: p for p in self.ok("prog.list")["items"]}
        self.assertEqual(progs["blink"]["params"]["on_ms"], 500)
        self.ok("param.set", key="blink.on_ms", value=1)
        self.ok("param.set", key="blink.off_ms", value=1)
        self.ok("prog.run", name="blink", args={"pin": 3})
        await self.settle()
        self.assertEqual(self.ok("info")["prog"], "blink")
        self.assertIn("3", self.hal.levels)
        self.ok("prog.stop")
        await self.settle()
        self.assertIsNone(self.ok("info")["prog"])
        self.ok("param.set", key="blink.on_ms", value=None)
        self.assertEqual(self.ctl.param("blink", "on_ms"), 500)

    async def test_program_that_finishes(self):
        self.ok("prog.run", name="timer", args={"pin": 4, "seconds": 0.01})
        await self.settle(0.05)
        self.assertEqual(self.hal.levels["4"], 0)
        self.assertIsNone(self.ctl.prog_name)
        self.assertIn({"ev": "prog_end", "name": "timer"}, self.events)

    async def test_program_error_is_reported(self):
        self.ok("prog.run", name="breathe", args={"pin": "LED"})
        await self.settle()
        self.assertEqual([e["ev"] for e in self.events], ["prog_err"])
        self.assertEqual(self.events[0]["err"], "LED can't do mode 'pwm'")
        self.assertIsNone(self.ctl.prog_name)
        self.assertNotIn("LED", self.ctl.pins)  # the failed attempt left nothing behind

    async def test_scheduled_program_with_duration_and_boot_program(self):
        self.ok("sched.put", item={"h": 5, "m": 0, "dur": 10,
                                   "act": {"type": "prog", "name": "blink"}})
        self.set_clock(5, 0)
        self.advance(1)
        self.assertEqual(self.ctl.prog_name, "blink")
        self.advance(11)
        self.assertIsNone(self.ctl.prog_name)

        self.ok("prog.boot", name="blink")
        self.ctl.save_now()
        self.ctl = self.make()
        task = asyncio.create_task(self.ctl.run())
        await self.settle()
        self.assertEqual(self.ctl.prog_name, "blink")
        self.ctl.stop_prog()
        task.cancel()


if __name__ == "__main__":
    unittest.main()

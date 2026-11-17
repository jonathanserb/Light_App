"""Command handling, pin state, schedules and programs.

Hardware-independent: everything that touches the board goes through the `hal`
object, so this file also runs under desktop Python for the tests in tests/.

Protocol: one JSON object per line in each direction.
    request   {"id": 7, "cmd": "pin.set", "pin": 15, "value": 1}
    response  {"id": 7, "ok": true}   or   {"id": 7, "ok": false, "err": "..."}
    event     {"ev": "fired", "sched": 3, "name": "Evening on"}
"""
import json
import os

try:
    import asyncio
except ImportError:  # MicroPython older than 1.21
    import uasyncio as asyncio

from programs import PROGRAMS

FW_VERSION = "1.0.0"
MAX_SCHEDULES = 32
MODES = ("in", "in_up", "in_down", "out", "pwm")
ACTIONS = ("pin", "pwm", "prog", "stop")
SAVE_DELAY_S = 2  # batch flash writes: save this long after the last change


class CmdError(Exception):
    pass


def _need(req, key):
    if key not in req or req[key] is None:
        raise CmdError("missing '%s'" % key)
    return req[key]


def _int(value, lo, hi, what):
    try:
        value = int(value)
    except (ValueError, TypeError):
        raise CmdError("%s must be a number" % what)
    if not lo <= value <= hi:
        raise CmdError("%s must be %d-%d" % (what, lo, hi))
    return value


class Ctx:
    """What a running program sees. See programs.py."""

    def __init__(self, ctl, name, args):
        self._ctl = ctl
        self._name = name
        self._args = args or {}

    def get(self, key):
        if key in self._args:
            return self._args[key]
        return self._ctl.param(self._name, key)

    def pin(self, pin, value):
        self._ctl.set_digital(self._ctl.key(pin), 1 if value else 0)

    def pwm(self, pin, duty):
        self._ctl.set_pwm(self._ctl.key(pin), duty)

    def read(self, pin):
        return self._ctl.read(self._ctl.key(pin))

    def adc(self, channel):
        return self._ctl.hal.adc_read(channel) * 3.3 / 65535

    def now(self):
        return self._ctl.hal.now()

    async def sleep(self, seconds):
        await asyncio.sleep(max(0, seconds))


class Controller:
    def __init__(self, hal, path="config.json"):
        self.hal = hal
        self.path = path
        self.emit = lambda event: None  # set by main.py to push events to the phone
        self.cfg = {"name": "Pico", "pass": "", "pins": {}, "sched": [],
                    "params": {}, "boot_prog": None}
        self._load()
        self.pins = self.cfg["pins"]
        self.sched = self.cfg["sched"]
        self.params = self.cfg["params"]
        self.authed = False
        self.prog_name = None
        self._prog_task = None
        self._reverts = []  # [due_ticks, kind, pin_key, value]
        self._last_minute = None
        self._save_at = None
        self._reset_at = None
        self._cmds = {
            "info": self._c_info, "auth": self._c_auth,
            "time.set": self._c_time_set, "name.set": self._c_name_set,
            "pass.set": self._c_pass_set, "reboot": self._c_reboot,
            "pin.list": self._c_pin_list, "pin.cfg": self._c_pin_cfg,
            "pin.set": self._c_pin_set, "pin.get": self._c_pin_get,
            "adc.get": self._c_adc_get,
            "sched.list": self._c_sched_list, "sched.put": self._c_sched_put,
            "sched.del": self._c_sched_del,
            "prog.list": self._c_prog_list, "prog.run": self._c_prog_run,
            "prog.stop": self._c_prog_stop, "prog.boot": self._c_prog_boot,
            "param.set": self._c_param_set,
        }
        for key in list(self.pins):
            try:
                self._apply(key)
            except Exception as e:
                print("pin restore failed", key, e)

    # ---- storage ---------------------------------------------------------

    def _load(self):
        try:
            with open(self.path) as f:
                saved = json.load(f)
            for k in self.cfg:
                if k == "boot_prog" or type(saved.get(k)) == type(self.cfg[k]):
                    self.cfg[k] = saved.get(k)
        except (OSError, ValueError):
            pass  # first boot or corrupt file: start with defaults

    def save_now(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.cfg, f)
        os.rename(tmp, self.path)  # atomic: a power cut can't leave half a file
        self._save_at = None

    def _dirty(self):
        self._save_at = self.hal.ticks() + SAVE_DELAY_S

    # ---- pins ------------------------------------------------------------

    def key(self, pin):
        """Normalise a pin id from the wire (15, "15", "LED") to a dict key."""
        if isinstance(pin, str) and pin.upper() == "LED":
            return "LED"
        try:
            n = int(pin)
        except (ValueError, TypeError):
            raise CmdError("bad pin")
        if not self.hal.valid_pin(n):
            raise CmdError("GP%d is not available" % n)
        return str(n)

    def _state(self, key):
        if key not in self.pins:
            self.pins[key] = {"mode": "in", "value": 0, "duty": 0, "freq": 1000, "label": ""}
        return self.pins[key]

    def _apply(self, key):
        st = self.pins[key]
        if st["mode"] == "pwm":
            self.hal.pwm_write(key, st["duty"], st["freq"])
        else:
            self.hal.pin_setup(key, st["mode"])
            if st["mode"] == "out":
                self.hal.pin_write(key, st["value"])

    def _drop_reverts(self, kind, key=None):
        self._reverts = [r for r in self._reverts if not (r[1] == kind and r[2] == key)]

    def set_digital(self, key, value):
        st = self._state(key)
        st["value"] = 1 if value else 0
        if st["mode"] != "out":
            st["mode"] = "out"
            self._apply(key)
        else:
            self.hal.pin_write(key, st["value"])

    def set_pwm(self, key, duty, freq=None):
        st = self._state(key)
        st["duty"] = min(100, max(0, duty))
        if freq:
            st["freq"] = freq
        st["mode"] = "pwm"
        self.hal.pwm_write(key, st["duty"], st["freq"])

    def read(self, key):
        st = self.pins.get(key)
        if st is None:
            raise CmdError("pin not configured")
        if st["mode"] == "out":
            return st["value"]
        if st["mode"] == "pwm":
            return st["duty"]
        st["value"] = self.hal.pin_read(key)
        return st["value"]

    # ---- programs --------------------------------------------------------

    def param(self, prog, key):
        stored = self.params.get(prog + "." + key)
        return PROGRAMS[prog][2][key] if stored is None else stored

    def start_prog(self, name, args=None):
        if name not in PROGRAMS:
            raise CmdError("no such program")
        self.stop_prog()
        self.prog_name = name
        self._prog_task = asyncio.create_task(self._run_prog(name, Ctx(self, name, args)))

    def stop_prog(self):
        if self._prog_task is not None:
            self._prog_task.cancel()
        self._prog_task = None
        self.prog_name = None

    async def _run_prog(self, name, ctx):
        me = asyncio.current_task()
        try:
            await PROGRAMS[name][0](ctx)
        except asyncio.CancelledError:
            raise
        except Exception as e:
            self.emit({"ev": "prog_err", "name": name, "err": "%s: %s" % (type(e).__name__, e)})
        if self._prog_task is me:  # finished on its own, not replaced or stopped
            self._prog_task = None
            self.prog_name = None
            self.emit({"ev": "prog_end", "name": name})

    # ---- schedules -------------------------------------------------------

    def _fire(self, s):
        act = s["act"]
        kind = act["type"]
        dur = s.get("dur", 0)
        due = self.hal.ticks() + dur
        if kind == "pin":
            key = self.key(act["pin"])
            self._drop_reverts("pin", key)
            self.set_digital(key, act["value"])
            if dur:
                self._reverts.append([due, "pin", key, 0 if act["value"] else 1])
        elif kind == "pwm":
            key = self.key(act["pin"])
            self._drop_reverts("pin", key)
            self.set_pwm(key, act["duty"])
            if dur:
                self._reverts.append([due, "pin", key, None])
        elif kind == "prog":
            self._drop_reverts("stop")
            self.start_prog(act["name"], act.get("args"))
            if dur:
                self._reverts.append([due, "stop", None, None])
        elif kind == "stop":
            self._drop_reverts("stop")
            self.stop_prog()
        self.emit({"ev": "fired", "sched": s["id"], "name": s.get("name", "")})

    def tick(self):
        """Call about once a second. Runs due schedules and housekeeping."""
        now = self.hal.now()
        ticks = self.hal.ticks()
        if now[0] >= 2024:  # clock has been set
            minute = tuple(now[0:5])
            if minute != self._last_minute:
                self._last_minute = minute
                for s in self.sched:
                    if (s["en"] and s["h"] == now[3] and s["m"] == now[4]
                            and (s["days"] >> now[6]) & 1):
                        try:
                            self._fire(s)
                        except Exception as e:
                            self.emit({"ev": "sched_err", "sched": s["id"], "err": str(e)})
        for r in [r for r in self._reverts if r[0] <= ticks]:
            self._reverts.remove(r)
            try:
                if r[1] == "stop":
                    self.stop_prog()
                elif r[3] is None:
                    self.set_pwm(r[2], 0)
                else:
                    self.set_digital(r[2], r[3])
            except Exception as e:
                self.emit({"ev": "sched_err", "err": str(e)})
        if self._save_at is not None and ticks >= self._save_at:
            self.save_now()
        if self._reset_at is not None and ticks >= self._reset_at:
            self.hal.reset()

    async def run(self):
        boot = self.cfg.get("boot_prog")
        if boot in PROGRAMS:
            self.start_prog(boot)
        while True:
            self.tick()
            await asyncio.sleep(1)

    # ---- connection ------------------------------------------------------

    @property
    def locked(self):
        return bool(self.cfg["pass"]) and not self.authed

    def on_disconnect(self):
        self.authed = False

    def handle_line(self, line):
        """Process one request line, return the response line (no newline)."""
        rid = None
        try:
            if isinstance(line, (bytes, bytearray)):
                line = bytes(line).decode()
            req = json.loads(line)
            rid = req.get("id")
            cmd = req.get("cmd")
            if self.locked and cmd not in ("info", "auth"):
                raise CmdError("locked")
            if cmd not in self._cmds:
                raise CmdError("unknown cmd")
            res = self._cmds[cmd](req) or {}
            res["ok"] = True
        except CmdError as e:
            res = {"ok": False, "err": str(e)}
        except Exception as e:  # never let a bad request crash the board
            res = {"ok": False, "err": "%s: %s" % (type(e).__name__, e)}
        res["id"] = rid
        return json.dumps(res)

    # ---- commands --------------------------------------------------------

    def _c_info(self, req):
        now = self.hal.now()
        return {"fw": FW_VERSION, "name": self.cfg["name"], "time": list(now[0:7]),
                "time_valid": now[0] >= 2024, "uptime": int(self.hal.ticks()),
                "temp_c": round(self.hal.temp_c(), 1), "mem_free": self.hal.mem_free(),
                "prog": self.prog_name, "boot_prog": self.cfg.get("boot_prog"),
                "n_sched": len(self.sched), "locked": self.locked,
                "has_pass": bool(self.cfg["pass"])}

    def _c_auth(self, req):
        if str(req.get("pass", "")) != self.cfg["pass"]:
            raise CmdError("wrong passcode")
        self.authed = True

    def _c_time_set(self, req):
        t = _need(req, "t")
        if not isinstance(t, list) or len(t) != 7:
            raise CmdError("t must be [year,month,day,hour,minute,second,weekday]")
        limits = ((2024, 2099), (1, 12), (1, 31), (0, 23), (0, 59), (0, 59), (0, 6))
        self.hal.set_time([_int(v, lo, hi, "time") for v, (lo, hi) in zip(t, limits)])

    def _c_name_set(self, req):
        name = str(_need(req, "name")).strip()
        if not 1 <= len(name) <= 20:
            raise CmdError("name must be 1-20 characters")
        self.cfg["name"] = name
        self._dirty()

    def _c_pass_set(self, req):
        self.cfg["pass"] = str(req.get("pass") or "")
        self.authed = True
        self._dirty()

    def _c_reboot(self, req):
        self.save_now()
        self._reset_at = self.hal.ticks() + 1  # leave time for the reply to go out

    def _pin_json(self, key):
        st = self.pins[key]
        if st["mode"] not in ("out", "pwm"):
            st["value"] = self.hal.pin_read(key)
        return {"pin": key, "mode": st["mode"], "value": st["value"], "duty": st["duty"],
                "freq": st["freq"], "label": st.get("label", "")}

    def _c_pin_list(self, req):
        return {"pins": [self._pin_json(k) for k in self.pins]}

    def _c_pin_cfg(self, req):
        key = self.key(_need(req, "pin"))
        mode = _need(req, "mode")
        if mode == "off":  # forget the pin and float it
            if key in self.pins:
                del self.pins[key]
                try:
                    self.hal.pin_setup(key, "in")
                except Exception:
                    pass  # e.g. the on-board LED can't be an input; it's forgotten either way
            self._drop_reverts("pin", key)
            self._dirty()
            return
        if mode not in MODES:
            raise CmdError("mode must be one of " + "/".join(MODES + ("off",)))
        is_new = key not in self.pins
        st = self._state(key)
        old = dict(st)
        st["mode"] = mode
        if "label" in req:
            st["label"] = str(req["label"])[:24]
        if "freq" in req:
            st["freq"] = _int(req["freq"], 8, 100000, "freq")
        try:
            self._apply(key)
        except Exception:
            if is_new:
                del self.pins[key]
            else:
                self.pins[key] = old
            raise CmdError("%s can't do mode '%s'" % ("LED" if key == "LED" else "GP" + key, mode))
        self._dirty()
        return {"pin": self._pin_json(key)}

    def _c_pin_set(self, req):
        key = self.key(_need(req, "pin"))
        self._drop_reverts("pin", key)
        if "duty" in req:
            self.set_pwm(key, _int(req["duty"], 0, 100, "duty"))
        else:
            self.set_digital(key, _int(_need(req, "value"), 0, 1, "value"))
        self._dirty()
        return {"pin": self._pin_json(key)}

    def _c_pin_get(self, req):
        key = self.key(_need(req, "pin"))
        return {"value": self.read(key)}

    def _c_adc_get(self, req):
        raw = self.hal.adc_read(_int(_need(req, "ch"), 0, 2, "ch"))
        return {"raw": raw, "volts": round(raw * 3.3 / 65535, 3)}

    def _c_sched_list(self, req):
        return {"items": self.sched}

    def _c_sched_put(self, req):
        src = _need(req, "item")
        act_in = _need(src, "act")
        kind = act_in.get("type")
        if kind not in ACTIONS:
            raise CmdError("action type must be one of " + "/".join(ACTIONS))
        act = {"type": kind}
        if kind in ("pin", "pwm"):
            key = self.key(_need(act_in, "pin"))
            act["pin"] = "LED" if key == "LED" else int(key)
        if kind == "pin":
            act["value"] = _int(_need(act_in, "value"), 0, 1, "value")
        elif kind == "pwm":
            act["duty"] = _int(_need(act_in, "duty"), 0, 100, "duty")
        elif kind == "prog":
            if _need(act_in, "name") not in PROGRAMS:
                raise CmdError("no such program")
            act["name"] = act_in["name"]
            if isinstance(act_in.get("args"), dict) and act_in["args"]:
                act["args"] = act_in["args"]
        item = {"name": str(src.get("name", ""))[:24], "en": bool(src.get("en", True)),
                "h": _int(_need(src, "h"), 0, 23, "hour"),
                "m": _int(_need(src, "m"), 0, 59, "minute"),
                "days": _int(src.get("days", 127), 1, 127, "days"),
                "dur": _int(src.get("dur", 0), 0, 86400, "duration"), "act": act}
        sid = src.get("id")
        existing = [s for s in self.sched if s["id"] == sid]
        if existing:
            item["id"] = sid
            self.sched[self.sched.index(existing[0])] = item
        else:
            if len(self.sched) >= MAX_SCHEDULES:
                raise CmdError("schedule list is full")
            item["id"] = max([s["id"] for s in self.sched] + [0]) + 1
            self.sched.append(item)
        self._dirty()
        return {"item": item}

    def _c_sched_del(self, req):
        sid = _need(req, "sid")  # not "id": that's the request id
        left = [s for s in self.sched if s["id"] != sid]
        if len(left) == len(self.sched):
            raise CmdError("no such schedule")
        self.sched[:] = left
        self._dirty()

    def _c_prog_list(self, req):
        items = []
        for name in sorted(PROGRAMS):
            desc, defaults = PROGRAMS[name][1], PROGRAMS[name][2]
            items.append({"name": name, "desc": desc,
                          "params": {k: self.param(name, k) for k in defaults}})
        return {"items": items, "running": self.prog_name, "boot": self.cfg.get("boot_prog")}

    def _c_prog_run(self, req):
        self._drop_reverts("stop")
        self.start_prog(_need(req, "name"), req.get("args"))

    def _c_prog_stop(self, req):
        self._drop_reverts("stop")
        self.stop_prog()

    def _c_prog_boot(self, req):
        name = req.get("name")
        if name is not None and name not in PROGRAMS:
            raise CmdError("no such program")
        self.cfg["boot_prog"] = name
        self._dirty()

    def _c_param_set(self, req):
        key = str(_need(req, "key"))
        value = req.get("value")
        if value is None:
            self.params.pop(key, None)  # back to the program's default
        elif isinstance(value, (bool, int, float, str)):
            self.params[key] = value
        else:
            raise CmdError("value must be a number, string or true/false")
        self._dirty()

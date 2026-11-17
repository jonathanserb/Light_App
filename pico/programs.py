"""User programs that run on the Pico.

Add your own here: decorate an async function with @program(name, description,
**default_params). Every keyword becomes a parameter that the phone app can
view, edit and save. Read parameters with ctx.get("key") *inside* your loop so
that changes sent from the phone take effect while the program is running.

ctx API:
    ctx.get(key)              current value of a parameter
    ctx.pin(pin, value)       drive a pin high/low (pin = GP number or "LED")
    ctx.pwm(pin, duty)        PWM a pin, duty 0-100 (%)
    ctx.read(pin)             read a pin
    ctx.adc(channel)          read ADC 0-2 as volts
    ctx.now()                 (year, month, day, hour, minute, second, weekday)
    await ctx.sleep(seconds)  always sleep with this, never time.sleep()
"""

PROGRAMS = {}


def program(name, desc="", **defaults):
    def register(fn):
        PROGRAMS[name] = (fn, desc, defaults)
        return fn
    return register


@program("blink", "Toggle a pin on and off forever", pin="LED", on_ms=500, off_ms=500)
async def blink(ctx):
    while True:
        ctx.pin(ctx.get("pin"), 1)
        await ctx.sleep(ctx.get("on_ms") / 1000)
        ctx.pin(ctx.get("pin"), 0)
        await ctx.sleep(ctx.get("off_ms") / 1000)


@program("timer", "Switch a pin on, wait, then switch it off", pin="LED", seconds=60)
async def timer(ctx):
    pin = ctx.get("pin")
    ctx.pin(pin, 1)
    try:
        await ctx.sleep(ctx.get("seconds"))
    finally:
        ctx.pin(pin, 0)


@program("breathe", "Fade a PWM pin up and down forever", pin=15, period_s=4, max_duty=100)
async def breathe(ctx):
    steps = 50
    while True:
        for i in list(range(steps + 1)) + list(range(steps - 1, 0, -1)):
            ctx.pwm(ctx.get("pin"), ctx.get("max_duty") * i / steps)
            await ctx.sleep(ctx.get("period_s") / (2 * steps))


@program("sunrise", "Slowly ramp a PWM pin from off to a target brightness",
         pin=15, minutes=20, max_duty=100)
async def sunrise(ctx):
    steps = 200
    for i in range(steps + 1):
        ctx.pwm(ctx.get("pin"), ctx.get("max_duty") * i / steps)
        await ctx.sleep(ctx.get("minutes") * 60 / steps)

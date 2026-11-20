"""The backend going down, timing out or rejecting must never lose events or stall the voice loop."""
import asyncio
from aiohttp import web
from backend import BackendClient


async def start(app):
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = site._server.sockets[0].getsockname()[1]
    return runner, f"http://127.0.0.1:{port}"


async def test_events_survive_a_backend_outage():
    received = []
    state = {"down": True}

    async def events(req):
        if state["down"]:
            return web.Response(status=503)
        body = await req.json()
        received.extend(body["events"])
        return web.json_response({"stored": len(body["events"])})

    app = web.Application()
    app.router.add_post("/agent/visits/v1/events", events)
    runner, url = await start(app)
    c = BackendClient(url, "tok", "v1", timeout=2)
    for i in range(10):
        c.event("reading", {"i": i})
    assert await c.flush() == 0  # down: nothing lost, still queued
    assert c.pending == 10
    state["down"] = False
    assert await c.flush() == 10
    assert c.pending == 0
    assert [e["data"]["i"] for e in received] == list(range(10))
    await c.close()
    await runner.cleanup()


async def test_rejected_batch_is_dropped_not_retried_forever():
    async def events(req):
        return web.json_response({"error": "bad"}, status=400)

    app = web.Application()
    app.router.add_post("/agent/visits/v1/events", events)
    runner, url = await start(app)
    c = BackendClient(url, "tok", "v1", timeout=2)
    c.event("note", {"text": "x"})
    await c.flush()
    assert c.pending == 0
    await c.close()
    await runner.cleanup()


async def test_slow_backend_times_out_without_blocking_event_queueing():
    async def events(req):
        await asyncio.sleep(5)
        return web.json_response({})

    app = web.Application()
    app.router.add_post("/agent/visits/v1/events", events)
    runner, url = await start(app)
    c = BackendClient(url, "tok", "v1", timeout=0.3)
    c.event("note", {"text": "x"})
    t = asyncio.get_event_loop().time()
    c.event("note", {"text": "queued instantly"})
    assert asyncio.get_event_loop().time() - t < 0.01
    await c.flush()
    assert c.pending == 2
    c._queue.clear()
    await c.close()
    await runner.cleanup()


async def test_context_retries_through_transient_errors():
    calls = {"n": 0}

    async def ctx(req):
        calls["n"] += 1
        if calls["n"] < 3:
            return web.Response(status=502)
        return web.json_response({"trade": {"id": "hvac"}})

    app = web.Application()
    app.router.add_get("/agent/visits/v1/context", ctx)
    runner, url = await start(app)
    c = BackendClient(url, "tok", "v1", timeout=2)
    data = await c.context()
    assert data["trade"]["id"] == "hvac"
    assert calls["n"] == 3
    await c.close()
    await runner.cleanup()


async def test_queue_is_bounded_in_a_very_long_outage():
    c = BackendClient("http://127.0.0.1:9", "tok", "v1", max_queue=100)
    for i in range(150):
        c.event("transcript", {"i": i})
    assert c.pending == 100
    assert c.dropped == 50
    c._queue.clear()
    await c.close()

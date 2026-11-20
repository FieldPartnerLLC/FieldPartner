"""HTTP client for the FieldPartner backend's agent API, with retries and an outbox.

The voice loop must never stall on the backend. Event writes are queued and flushed
in the background; a backend outage holds events in memory and retries until the
visit ends, so a 30 second blip loses nothing.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from collections import deque
from typing import Any

import aiohttp

logger = logging.getLogger("fieldpartner.backend")


class BackendClient:
    def __init__(self, base_url: str, token: str, visit_id: str, *, timeout: float = 10.0, max_queue: int = 5000) -> None:
        self.base = base_url.rstrip("/")
        self.token = token
        self.visit_id = visit_id
        self.timeout = aiohttp.ClientTimeout(total=timeout)
        self._queue: deque[dict[str, Any]] = deque(maxlen=max_queue)
        self._session: aiohttp.ClientSession | None = None
        self._flush_lock = asyncio.Lock()
        self._pump: asyncio.Task | None = None
        self.dropped = 0

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"}

    async def _http(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(timeout=self.timeout)
        return self._session

    async def _request(self, method: str, path: str, json_body: Any = None, attempts: int = 3) -> Any:
        delay = 0.5
        last: Exception | None = None
        for attempt in range(attempts):
            try:
                http = await self._http()
                async with http.request(method, f"{self.base}{path}", headers=self._headers(), json=json_body) as resp:
                    if resp.status >= 500:
                        raise aiohttp.ClientResponseError(resp.request_info, resp.history, status=resp.status)
                    data = await resp.json(content_type=None)
                    if resp.status >= 400:
                        # 4xx will not succeed on retry: report and give up.
                        raise PermissionError(f"{method} {path} -> {resp.status}: {data}")
                    return data
            except PermissionError:
                raise
            except Exception as e:  # network, timeout, 5xx
                last = e
                if attempt < attempts - 1:
                    await asyncio.sleep(delay)
                    delay *= 2
        raise ConnectionError(f"{method} {path} failed after {attempts} attempts: {last}")

    async def context(self) -> dict[str, Any]:
        return await self._request("GET", f"/agent/visits/{self.visit_id}/context", attempts=5)

    async def escalate(self, reason: str) -> dict[str, Any]:
        return await self._request("POST", f"/agent/visits/{self.visit_id}/escalate", {"reason": reason})

    def event(self, type_: str, data: dict[str, Any], *, actor: str = "ai") -> str:
        """Queues an event. Returns its id. Never blocks, never raises."""
        eid = f"ai-{uuid.uuid4()}"
        if len(self._queue) == self._queue.maxlen:
            self.dropped += 1
        self._queue.append({"id": eid, "type": type_, "actor": actor, "data": data})
        return eid

    async def flush(self) -> int:
        async with self._flush_lock:
            sent = 0
            while self._queue:
                batch = list(self._queue)[:200]
                try:
                    await self._request("POST", f"/agent/visits/{self.visit_id}/events", {"events": batch}, attempts=2)
                except PermissionError as e:
                    logger.error("backend rejected events, dropping batch: %s", e)
                    for _ in batch:
                        self._queue.popleft()
                    continue
                except ConnectionError as e:
                    logger.warning("backend unreachable, will retry: %s", e)
                    return sent
                for _ in batch:
                    self._queue.popleft()
                sent += len(batch)
            return sent

    def start_pump(self, every: float = 2.0) -> None:
        async def pump() -> None:
            while True:
                await asyncio.sleep(every)
                try:
                    await self.flush()
                except Exception:
                    logger.exception("event flush failed")

        if self._pump is None:
            self._pump = asyncio.create_task(pump())

    async def close(self) -> None:
        if self._pump:
            self._pump.cancel()
            self._pump = None
        for attempt in range(4):
            await self.flush()
            if not self._queue:
                break
            await asyncio.sleep(1.5 * (attempt + 1))
        if self._queue:
            logger.error("closing with %d unsent events", len(self._queue))
        if self._session and not self._session.closed:
            await self._session.close()

    @property
    def pending(self) -> int:
        return len(self._queue)

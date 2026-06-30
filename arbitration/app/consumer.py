import asyncio
import logging

import aio_pika

from app.callback import post_ruling
from app.graph.build import run_arbitration
from app.schemas import ArbitrationRequest

log = logging.getLogger("arbitration.consumer")

_MAX_ATTEMPTS = 3


async def _arbitrate_with_retry(graph, request, settings):
    delay = 1.0
    for attempt in range(1, _MAX_ATTEMPTS + 1):
        try:
            result = await run_arbitration(graph, request)
            status = await post_ruling(
                settings.backend_base_url, request.dispute_id,
                settings.arbitration_callback_secret, result,
                timeout=settings.callback_timeout_seconds)
            return status
        except Exception as e:  # noqa: BLE001 - retry transient, give up after the cap
            if attempt == _MAX_ATTEMPTS:
                raise
            log.warning("arbitration attempt %d failed (%s); retrying", attempt, e)
            await asyncio.sleep(delay)
            delay *= 2


async def handle_message(message, *, settings, graph) -> None:
    try:
        request = ArbitrationRequest.model_validate_json(message.body)
    except Exception:  # noqa: BLE001 - poison message
        log.exception("un-parseable arbitration message; dead-lettering")
        await message.nack(requeue=False)
        return
    try:
        await _arbitrate_with_retry(graph, request, settings)
        await message.ack()  # ack ONLY after the ruling callback returned 2xx
        log.info("dispute %s ruled and acknowledged", request.dispute_id)
    except Exception:  # noqa: BLE001 - persistent failure → DLQ → Java fallback refund
        log.exception("arbitration failed for dispute %s; dead-lettering to fallback",
                      request.dispute_id)
        await message.nack(requeue=False)


async def run_consumer(settings, graph, stop_event: asyncio.Event) -> None:
    connection = await aio_pika.connect_robust(settings.rabbitmq_url)
    try:
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=4)
        # The Java backend owns the topology; declare passively so we never re-arg it.
        queue = await channel.declare_queue(settings.dispute_queue, passive=True)
        async with queue.iterator() as it:
            async for message in it:
                if stop_event.is_set():
                    break
                await handle_message(message, settings=settings, graph=graph)
    finally:
        await connection.close()

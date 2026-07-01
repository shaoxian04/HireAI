import asyncio
import contextlib
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from langchain_openai import ChatOpenAI

from app.config import load_settings
from app.consumer import run_consumer
from app.graph.build import build_graph

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = load_settings()
    if settings.openai_api_key and settings.arbitration_callback_secret:
        llm = ChatOpenAI(
            model=settings.openai_model, api_key=settings.openai_api_key, temperature=0
        )
        graph = build_graph(llm, settings)
        stop_event = asyncio.Event()
        task = asyncio.create_task(run_consumer(settings, graph, stop_event))
        app.state.consumer_task = task
    else:
        logging.getLogger("arbitration").warning(
            "consumer disabled — missing OPENAI/secret config"
        )
        stop_event = asyncio.Event()
        app.state.consumer_task = None
    try:
        yield
    finally:
        stop_event.set()
        if app.state.consumer_task is not None:
            app.state.consumer_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await app.state.consumer_task


app = FastAPI(title="HireAI Arbitration Worker", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}

from functools import partial

from langgraph.graph import END, START, StateGraph

from app.graph.nodes import classify, critique, deliberate, gather_evidence
from app.graph.state import ArbitrationState
from app.schemas import ArbitrationRequest, RulingResult


def build_graph(llm, settings):
    g = StateGraph(ArbitrationState)
    g.add_node("gather_evidence", partial(gather_evidence,
               max_bytes=settings.fetch_max_bytes, timeout=settings.fetch_timeout_seconds))
    g.add_node("deliberate", partial(deliberate, llm=llm))
    g.add_node("classify", partial(classify, llm=llm))
    g.add_node("critique", partial(critique, llm=llm))
    g.add_edge(START, "gather_evidence")
    g.add_edge("gather_evidence", "deliberate")
    g.add_edge("deliberate", "classify")
    g.add_edge("classify", "critique")
    g.add_edge("critique", END)
    return g.compile()


async def run_arbitration(graph, request: ArbitrationRequest) -> RulingResult:
    final = await graph.ainvoke({"request": request})
    return RulingResult(category=final["category"], rationale=final["rationale"])

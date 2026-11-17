"""FILE WorkOrders get a resultUrl: a #deliver:<url> tag in the description, else the env."""
import app as agent


def test_a_text_work_order_has_no_file():
    assert agent.file_url_for({"outputSpec": {"format": "TEXT"}, "description": "#deliver:https://x"}) is None


def test_the_description_tag_names_the_file(monkeypatch):
    monkeypatch.setenv(agent.FILE_URL_ENV, "https://env.example/a.png")
    order = {"outputSpec": {"format": "FILE"}, "description": "Draw it #deliver:https://cdn.example/b.png ok"}
    assert agent.file_url_for(order) == "https://cdn.example/b.png"


def test_without_a_tag_the_env_names_the_file(monkeypatch):
    monkeypatch.setenv(agent.FILE_URL_ENV, "https://env.example/a.png")
    assert agent.file_url_for({"outputSpec": {"format": "FILE"}, "description": "Draw it"}) == (
        "https://env.example/a.png"
    )


def test_a_rework_work_order_delivers_the_rework_link():
    order = {"outputSpec": {"format": "FILE"},
             "description": "Logo #deliver:https://a.example/red.png #rework:https://a.example/white.png"}
    assert agent.file_url_for(order) == "https://a.example/red.png"
    assert agent.file_url_for({**order, "rework": {"reworkNo": 1}}) == "https://a.example/white.png"


def test_an_anomaly_tag_self_reports_against_that_check():
    assert agent.anomalies_for({"description": "Logo #anomaly:IMAGE_BACKGROUND_COLOUR"}) == [
        {"check": "IMAGE_BACKGROUND_COLOUR",
         "description": "The demo agent flags this: the brief makes it hard to meet as written."}]
    assert agent.anomalies_for({"description": "Logo"}) == []

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

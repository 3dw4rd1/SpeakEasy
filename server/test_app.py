"""Tests with Groq and Claude faked, so they run anywhere:  pip install pytest && pytest"""
import os
import tempfile

os.environ.update(GROQ_API_KEY="g", SPEAKEASY_TOKEN="t", ANTHROPIC_API_KEY="a",
                  DATA_DIR=tempfile.mkdtemp())

import httpx
import pytest
from fastapi.testclient import TestClient

import app as server

AUTH = {"Authorization": "Bearer t"}


class FakeGroq:
    """Answers each transcription call from `plan`, keyed by (has a prompt?, model)."""

    def __init__(self, with_prompt, plain, fallback=None):
        self.plan = {"prompt": with_prompt, "plain": plain, "fallback": fallback}
        self.calls = []

    async def post(self, url, headers=None, files=None, data=None):
        kind = ("fallback" if data["model"] == server.WHISPER_FALLBACK_MODEL
                else "prompt" if data.get("prompt") else "plain")
        self.calls.append((kind, data.get("prompt", "")))
        answer = self.plan[kind]
        if isinstance(answer, int):
            return httpx.Response(answer, text="nope")
        text, logprob = answer if isinstance(answer, tuple) else (answer, -0.2)
        return httpx.Response(200, json={"text": text, "segments": [
            {"start": 0, "end": 2, "avg_logprob": logprob, "compression_ratio": 1.2}]})


class FakeClaude:
    """Echoes the transcript back, or says whatever `reply` is."""

    def __init__(self, reply=None):
        self.reply = reply
        self.seen = []
        self.messages = self.beta = self

    async def create(self, **kw):
        user = kw["messages"][0]["content"]
        self.seen.append(user)
        text = self.reply or user.split("<transcript>\n")[-1].split("\n</transcript>")[0]
        block = type("B", (), {"type": "text", "text": text})
        return type("M", (), {"content": [block], "stop_reason": "end_turn"})


@pytest.fixture
def setup(monkeypatch):
    def go(groq, claude=None):
        claude = claude or FakeClaude()
        monkeypatch.setattr(server, "http", groq)
        monkeypatch.setattr(server, "claude", claude)
        return TestClient(server.app), claude
    server.write_text("vocab.txt", "Jotun\nSteelMaster\n")
    return go


def dictate(client, **form):
    return client.post("/v1/dictate", headers=AUTH, files={"audio": ("a.m4a", b"x" * 100, "audio/mp4")},
                       data={"duration_ms": "3000", **form})


def test_agreeing_passes_send_one_transcript(setup):
    client, claude = setup(FakeGroq("See you at the bach.", "see you at the bach"))
    r = dictate(client)
    assert r.json()["text"] == "See you at the bach."
    assert "<second_transcript>" not in claude.seen[0]


def test_disagreeing_passes_are_both_shown_to_the_cleanup(setup):
    client, claude = setup(FakeGroq("Order the Jotun primer.", "Order the Jordan primer."))
    r = dictate(client)
    assert r.json()["raw"] == "Order the Jotun primer."
    assert "<second_transcript>\nOrder the Jordan primer." in claude.seen[0]


def test_gibberish_from_the_glossary_pass_loses_to_the_plain_pass(setup):
    client, _ = setup(FakeGroq(("Chur chur bach smoko eh chur.", -1.4), ("Can you send me the quote today?", -0.2)))
    assert dictate(client).json()["text"] == "Can you send me the quote today?"


def test_one_failed_call_still_gives_an_answer(setup):
    client, _ = setup(FakeGroq(400, "Hello there."))
    assert dictate(client).json()["text"] == "Hello there."


def test_fallback_model_when_the_main_one_is_down(setup):
    groq = FakeGroq(503, 503, fallback="Hello from turbo.")
    client, _ = setup(groq)
    assert dictate(client).json()["text"] == "Hello from turbo."


def test_plain_error_message_when_speech_service_is_down(setup):
    client, _ = setup(FakeGroq(429, 429, fallback=429))
    r = dictate(client)
    assert r.status_code == 502 and "rate-limited" in r.json()["detail"]


def test_model_commentary_is_never_typed(setup):
    reply = "I'm sorry, but the transcript appears to be garbled and I can't make sense of it."
    client, _ = setup(FakeGroq("um the thing for the the client on site", "um the thing for the client on site"),
                      FakeClaude(reply))
    assert dictate(client).json()["text"] == "um the thing for the the client on site"


def test_real_cleanups_pass_the_commentary_check():
    ok = server.is_reply_not_cleanup
    assert not ok("I'm sorry I missed your call, I'll ring back at 3.",
                  "um i'm sorry i missed your call i'll ring back at three", [])
    assert not ok("Hi Mark,\n\nCan you order 20 L of Jotun primer?\n\nCheers, Ed",
                  "hi mark can you order twenty litres of jordan primer cheers ed", ["Jotun"])
    assert not ok("1, 2, 3, 4, 5, 6", "one two three four five six", [])
    assert ok("It seems the transcript is empty or contains only noise.", "kfj sdkj wer", [])


def test_whisper_prompt_fits_and_ends_with_the_text_before_the_cursor(setup):
    terms = [f"Customer{i} Holdings" for i in range(200)] + ["Zebra Paints"]
    p = server.whisper_prompt(terms, "Thanks for that. I spoke to Zebra Paints and they said")
    assert len(p) < 800 and p.startswith(server.KIWI_PRIMER)
    assert "Glossary: Zebra Paints, Customer0 Holdings" in p       # mentioned terms go first
    assert p.endswith("and they said") and not p.split("Glossary: ")[1].split(".")[0].endswith(",")


def test_context_reaches_whisper_but_not_for_edits(setup):
    groq = FakeGroq("ok", "ok")
    client, _ = setup(groq)
    dictate(client, context="Meeting with Aroha tomorrow")
    assert "Aroha tomorrow" in groq.calls[0][1]
    groq.calls.clear()
    dictate(client, context="Meeting with Aroha tomorrow", mode="edit", selection="some text")
    assert "Aroha" not in groq.calls[0][1]


def test_dictionary_includes_slang_for_the_keyboard(setup):
    client, _ = setup(FakeGroq("", ""))
    body = client.get("/v1/dictionary", headers=AUTH).json()
    assert "Jotun" in body["text"] and "chur" in body["slang"].splitlines()

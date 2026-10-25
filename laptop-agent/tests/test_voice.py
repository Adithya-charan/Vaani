from __future__ import annotations

import asyncio
import base64
import json

import httpx
import pytest

from app.services.sarvam import SarvamVoice, Transcript, VoiceError
from tests.conftest import AUTH

WAV = b"RIFF" + b"\x00" * 4 + b"WAVEfmt " + b"\x00" * 2048


class FakeVoice:
    def __init__(self, transcript: str = "open calculator", error: VoiceError | None = None) -> None:
        self.transcript = transcript
        self.error = error
        self.calls: list[tuple] = []

    async def transcribe(self, audio, filename, content_type, language="unknown"):
        self.calls.append(("stt", len(audio), language))
        if self.error:
            raise self.error
        return Transcript(self.transcript, "en-IN")

    async def speak(self, text, language="en-IN", speaker=None):
        self.calls.append(("tts", text, language))
        if self.error:
            raise self.error
        return WAV


def upload(client, data=WAV, headers=AUTH, **form):
    return client.post("/voice/transcribe", files={"file": ("cmd.wav", data, "audio/wav")}, data=form, headers=headers)


# --- endpoints -------------------------------------------------------------------

def test_voice_requires_token(make_client):
    client = make_client(voice=FakeVoice())
    assert upload(client, headers={}).status_code == 401
    assert client.post("/voice/speak", json={"text": "hi"}).status_code == 401


def test_voice_not_configured(make_client):
    client = make_client()
    r = upload(client)
    assert r.status_code == 503
    assert "SARVAM_API_KEY" in r.json()["detail"]
    assert client.get("/status", headers=AUTH).json()["data"]["voice"] is False


def test_transcribe(make_client):
    voice = FakeVoice("Open Calculator")
    client = make_client(voice=voice)
    r = upload(client, language="te-IN")
    assert r.status_code == 200
    assert r.json() == {"transcript": "Open Calculator", "language_code": "en-IN"}
    assert voice.calls == [("stt", len(WAV), "te-IN")]
    assert client.get("/status", headers=AUTH).json()["data"]["voice"] is True


def test_transcribe_does_not_execute_anything(make_client, launcher):
    client = make_client(voice=FakeVoice("open calculator"))
    upload(client)
    assert launcher.calls == []


@pytest.mark.parametrize("size,status", [(10, 422), (2 * 1024 * 1024 + 1, 413)], ids=["too_short", "too_long"])
def test_transcribe_size_limits(make_client, size, status):
    voice = FakeVoice()
    client = make_client(voice=voice)
    assert upload(client, data=b"x" * size).status_code == status
    assert voice.calls == []


def test_transcribe_bad_language(make_client):
    assert upload(make_client(voice=FakeVoice()), language="xx-YY").status_code == 422


def test_voice_service_errors_are_reported(make_client):
    client = make_client(voice=FakeVoice(error=VoiceError("The voice service is busy.", 503)))
    r = upload(client)
    assert r.status_code == 503
    assert r.json()["detail"] == "The voice service is busy."


def test_speak_returns_wav(make_client):
    voice = FakeVoice()
    client = make_client(voice=voice)
    r = client.post("/voice/speak", json={"text": "Calculator opened.", "language": "hi-IN"}, headers=AUTH)
    assert r.status_code == 200
    assert r.headers["content-type"] == "audio/wav"
    assert r.content == WAV
    assert voice.calls == [("tts", "Calculator opened.", "hi-IN")]


@pytest.mark.parametrize("body", [{"text": ""}, {"text": "x" * 501}, {"text": "hi", "language": "fr-FR"},
                                  {"text": "hi", "speaker": "someone"}])
def test_speak_validation(make_client, body):
    assert make_client(voice=FakeVoice()).post("/voice/speak", json=body, headers=AUTH).status_code == 422


# --- Sarvam client ------------------------------------------------------------------

def _voice(handler) -> SarvamVoice:
    return SarvamVoice("sk_test", "saaras:v4", "bulbul:v4-flash", keyterms=["Desktop", "Projects"],
                       client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))


def test_stt_request_and_parse():
    seen = {}

    def handler(request: httpx.Request):
        seen["url"] = str(request.url)
        seen["key"] = request.headers["api-subscription-key"]
        seen["body"] = request.content
        return httpx.Response(200, json={"request_id": "r", "transcript": "  Open Calculator. ",
                                         "language_code": "en-IN", "language_probability": 1.0})

    t = asyncio.run(_voice(handler).transcribe(WAV, "cmd.wav", "audio/wav"))
    assert t == Transcript("Open Calculator.", "en-IN")
    assert seen["url"] == "https://api.sarvam.ai/speech-to-text"
    assert seen["key"] == "sk_test"
    assert b'name="model"\r\n\r\nsaaras:v4' in seen["body"]
    assert b'name="language_code"\r\n\r\nunknown' in seen["body"]
    keyterms_part = seen["body"].split(b'name="keyterms"\r\n\r\n')[1].split(b"\r\n")[0]
    terms = json.loads(keyterms_part)
    assert "Calculator" in terms and "Projects" in terms and terms.count("Desktop") == 1


def test_tts_decodes_audios_array():
    seen = {}

    def handler(request: httpx.Request):
        seen["json"] = json.loads(request.content)
        return httpx.Response(200, json={"request_id": "r", "audios": [base64.b64encode(WAV).decode()]})

    assert asyncio.run(_voice(handler).speak("Hello", "te-IN")) == WAV
    assert seen["json"]["model"] == "bulbul:v4-flash"
    assert seen["json"]["language_code"] == "te-IN"
    assert "target_language_code" not in seen["json"]


@pytest.mark.parametrize("response,status,msg", [
    (httpx.Response(403, json={"error": {"message": "bad key"}}), 502, "API key"),
    (httpx.Response(429, json={}), 503, "busy"),
    (httpx.Response(400, json={}), 422, "could not process"),
    (httpx.Response(500, json={}), 502, "HTTP 500"),
    (httpx.Response(200, json={"audio_content": "abc"}), 502, "unreadable"),
    (httpx.Response(200, json={"audios": ["not base64!!"]}), 502, "unreadable"),
    (httpx.Response(200, json={"audios": [base64.b64encode(b"ID3 mp3 data").decode()]}), 502, "unexpected format"),
])
def test_tts_errors(response, status, msg):
    with pytest.raises(VoiceError, match=msg) as exc:
        asyncio.run(_voice(lambda r: response).speak("x"))
    assert exc.value.status == status


def test_network_errors():
    def down(request):
        raise httpx.ConnectError("down")

    def slow(request):
        raise httpx.ReadTimeout("slow")

    with pytest.raises(VoiceError) as exc:
        asyncio.run(_voice(down).transcribe(WAV, "a.wav", "audio/wav"))
    assert exc.value.status == 503
    with pytest.raises(VoiceError) as exc:
        asyncio.run(_voice(slow).speak("x"))
    assert exc.value.status == 504


def test_stt_unexpected_reply():
    with pytest.raises(VoiceError):
        asyncio.run(_voice(lambda r: httpx.Response(200, json={"text": "x"})).transcribe(WAV, "a.wav", "audio/wav"))

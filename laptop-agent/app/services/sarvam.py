"""Sarvam speech-to-text (Saaras) and text-to-speech (Bulbul), called only from the laptop.

The API key stays here; the phone never sees it.
"""

from __future__ import annotations

import base64
import binascii
import json
from dataclasses import dataclass

import httpx

STT_URL = "https://api.sarvam.ai/speech-to-text"
TTS_URL = "https://api.sarvam.ai/text-to-speech"

STT_LANGUAGES = {
    "unknown", "en-IN", "hi-IN", "bn-IN", "gu-IN", "kn-IN", "ml-IN", "mr-IN", "od-IN", "pa-IN", "ta-IN", "te-IN",
}
TTS_LANGUAGES = {"en-IN", "hi-IN", "bn-IN", "gu-IN", "kn-IN", "ml-IN", "mr-IN", "od-IN", "pa-IN", "ta-IN", "te-IN"}

# Words Vaani commands commonly contain, so "Calculator" isn't heard as "Calculus".
BASE_KEYTERMS = ["Vaani", "Calculator", "Notepad", "File Explorer", "PDF", "Downloads", "Documents", "Desktop"]


class VoiceError(Exception):
    """Voice service failed. ``status`` is the HTTP status the agent should return."""

    def __init__(self, message: str, status: int = 502) -> None:
        super().__init__(message)
        self.message = message
        self.status = status


@dataclass
class Transcript:
    text: str
    language_code: str | None


class SarvamVoice:
    def __init__(
        self,
        api_key: str,
        stt_model: str,
        tts_model: str,
        keyterms: list[str] | None = None,
        client: httpx.AsyncClient | None = None,
        timeout: float = 25.0,
    ) -> None:
        self._headers = {"api-subscription-key": api_key}
        self.stt_model = stt_model
        self.tts_model = tts_model
        self._keyterms = list(dict.fromkeys(BASE_KEYTERMS + (keyterms or [])))[:50]
        self._client = client
        self._timeout = timeout

    async def _post(self, url: str, **kwargs) -> httpx.Response:
        try:
            if self._client is not None:
                resp = await self._client.post(url, headers=self._headers, **kwargs)
            else:
                async with httpx.AsyncClient(timeout=self._timeout) as client:
                    resp = await client.post(url, headers=self._headers, **kwargs)
        except httpx.TimeoutException as exc:
            raise VoiceError("The voice service took too long to respond.", 504) from exc
        except httpx.HTTPError as exc:
            raise VoiceError("The laptop could not reach the voice service. Check its internet connection.", 503) from exc
        if resp.status_code in (401, 403):
            raise VoiceError("The voice service rejected the laptop's Sarvam API key.", 502)
        if resp.status_code == 429:
            raise VoiceError("The voice service is busy or out of quota. Try again shortly, or type.", 503)
        if resp.status_code == 400 or resp.status_code == 422:
            raise VoiceError("The voice service could not process that audio.", 422)
        if resp.status_code >= 400:
            raise VoiceError(f"The voice service failed (HTTP {resp.status_code}).", 502)
        return resp

    async def transcribe(self, audio: bytes, filename: str, content_type: str, language: str = "unknown") -> Transcript:
        data = {"model": self.stt_model, "language_code": language}
        if self.stt_model.startswith("saaras:v4") and self._keyterms:
            data["keyterms"] = json.dumps(self._keyterms)
        resp = await self._post(STT_URL, files={"file": (filename, audio, content_type)}, data=data)
        try:
            body = resp.json()
            text = body["transcript"]
        except (ValueError, KeyError, TypeError) as exc:
            raise VoiceError("The voice service returned an unexpected reply.") from exc
        if not isinstance(text, str):
            raise VoiceError("The voice service returned an unexpected reply.")
        return Transcript(text=text.strip(), language_code=body.get("language_code"))

    async def speak(self, text: str, language: str = "en-IN", speaker: str | None = None) -> bytes:
        payload: dict = {
            "text": text,
            "language_code": language,
            "model": self.tts_model,
            "speech_sample_rate": 22050,
        }
        if speaker:
            payload["speaker"] = speaker
        resp = await self._post(TTS_URL, json=payload)
        try:
            audios = resp.json()["audios"]
            # Sarvam returns base64 WAV in an "audios" array, not "audio_content".
            wav = base64.b64decode(audios[0], validate=True)
        except (ValueError, KeyError, IndexError, TypeError, binascii.Error) as exc:
            raise VoiceError("The voice service returned unreadable audio.") from exc
        if wav[:4] != b"RIFF":
            raise VoiceError("The voice service returned audio in an unexpected format.")
        return wav

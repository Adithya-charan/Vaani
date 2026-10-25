"""Authenticated voice endpoints: phone audio -> text, and reply text -> speech."""

from __future__ import annotations

import logging
from typing import Literal

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from app.security.auth import require_token
from app.services.sarvam import STT_LANGUAGES, VoiceError

audit = logging.getLogger("vaani.audit")

MAX_AUDIO_BYTES = 2 * 1024 * 1024  # ~60 s of 16 kHz mono 16-bit WAV; Sarvam's REST limit is 30 s
MIN_AUDIO_BYTES = 1024

router = APIRouter(prefix="/voice", dependencies=[Depends(require_token)])

TtsLanguage = Literal["en-IN", "hi-IN", "bn-IN", "gu-IN", "kn-IN", "ml-IN", "mr-IN", "od-IN", "pa-IN", "ta-IN", "te-IN"]


class TranscriptResponse(BaseModel):
    transcript: str
    language_code: str | None = None


class SpeakRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=500)
    language: TtsLanguage = "en-IN"


def _voice(request: Request):
    voice = request.app.state.voice
    if voice is None:
        raise HTTPException(503, "Voice is not set up on the laptop (SARVAM_API_KEY is missing).")
    return voice


@router.post("/transcribe", response_model=TranscriptResponse)
async def transcribe(
    request: Request,
    file: UploadFile = File(...),
    language: str = Form("unknown"),
) -> TranscriptResponse:
    voice = _voice(request)
    if language not in STT_LANGUAGES:
        raise HTTPException(422, "Unsupported language.")
    audio = await file.read(MAX_AUDIO_BYTES + 1)
    if len(audio) > MAX_AUDIO_BYTES:
        raise HTTPException(413, "That recording is too long. Keep voice commands under 30 seconds.")
    if len(audio) < MIN_AUDIO_BYTES:
        raise HTTPException(422, "That recording is too short.")
    try:
        result = await voice.transcribe(audio, file.filename or "command.wav",
                                        file.content_type or "audio/wav", language)
    except VoiceError as exc:
        audit.info("action=voice_transcribe status=error")
        raise HTTPException(exc.status, exc.message) from exc
    # Audit only the outcome, never the transcript.
    audit.info("action=voice_transcribe status=success empty=%s", not result.text)
    return TranscriptResponse(transcript=result.text, language_code=result.language_code)


@router.post("/speak")
async def speak(body: SpeakRequest, request: Request) -> Response:
    voice = _voice(request)
    try:
        wav = await voice.speak(body.text, body.language)
    except VoiceError as exc:
        audit.info("action=voice_speak status=error")
        raise HTTPException(exc.status, exc.message) from exc
    audit.info("action=voice_speak status=success")
    return Response(content=wav, media_type="audio/wav", headers={"Cache-Control": "no-store"})

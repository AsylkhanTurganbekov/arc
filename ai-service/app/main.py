import uuid
from contextlib import asynccontextmanager

import httpx
from fastapi import Depends, FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from .config import get_settings
from .database import Conversation, SessionLocal, get_session, init_database
from .schemas import ChatRequest, ChatResponse, ConversationResponse, MessageResponse
from .service import ArcAiService, seed_model_config


@asynccontextmanager
async def lifespan(_: FastAPI):
    await init_database()
    async with SessionLocal() as session:
        await seed_model_config(session)
    yield


app = FastAPI(title="A.R.C. AI Service", version="0.1.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origin_regex=r"http://(localhost|127\.0\.0\.1)(:\d+)?",
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)
service = ArcAiService()


@app.get("/health")
async def health() -> dict:
    settings = get_settings()
    backend = "UNAVAILABLE"
    try:
        async with httpx.AsyncClient(timeout=4.0) as client:
            response = await client.get(f"{settings.arc_backend_url}/api/health")
            if response.is_success:
                backend = "CONNECTED"
    except httpx.HTTPError:
        pass
    return {
        "status": "UP",
        "backend": backend,
        "llm": "CONFIGURED" if settings.nitec_llm_api_key else "KEY_REQUIRED",
        "model": settings.nitec_llm_model,
    }


@app.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest, session: AsyncSession = Depends(get_session)) -> ChatResponse:
    return await service.chat(request, session)


@app.get("/conversations/{conversation_id}", response_model=ConversationResponse)
async def conversation(
    conversation_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
) -> ConversationResponse:
    result = await session.execute(
        select(Conversation)
        .options(selectinload(Conversation.messages))
        .where(Conversation.id == conversation_id)
    )
    item = result.scalar_one_or_none()
    if item is None:
        raise HTTPException(status_code=404, detail="Conversation not found")
    messages = sorted(item.messages, key=lambda message: (message.created_at, message.id))
    return ConversationResponse(
        id=item.id,
        title=item.title,
        messages=[MessageResponse(role=message.role, content=message.content, created_at=message.created_at) for message in messages],
    )

import uuid
from datetime import datetime

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    conversation_id: uuid.UUID | None = None


class FactSource(BaseModel):
    tool: str
    arguments: dict


class ChatResponse(BaseModel):
    conversation_id: uuid.UUID
    message: str
    facts_used: list[FactSource]
    model: str


class MessageResponse(BaseModel):
    role: str
    content: str
    created_at: datetime


class ConversationResponse(BaseModel):
    id: uuid.UUID
    title: str
    messages: list[MessageResponse]

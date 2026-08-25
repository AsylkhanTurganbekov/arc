import asyncio
import json
import time
import uuid
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .config import get_settings
from .database import Conversation, Message, ModelConfig, ToolCallLog
from .schemas import ChatRequest, ChatResponse, FactSource
from .tools import TOOLS, execute_tool


SYSTEM_PROMPT = """Ты A.R.C. — AI Resource Command, AI-помощник CTO и руководителя портфеля.

Твои задачи: анализировать проекты, команды, спринты, риски, ресурсы и релизы; предлагать управленческие действия.

Правила:
1. Не придумывай метрики. Используй факты только из tools.
2. Если фактов недостаточно — вызови дополнительный tool.
3. Структурируй ответ тремя короткими секциями: ФАКТ, АНАЛИЗ, РЕКОМЕНДАЦИЯ.
4. Не утверждай причинно-следственную связь без подтверждения.
5. Не изменяй Jira. What-if всегда обозначай как симуляцию.
6. При анализе сотрудника учитывай capacity, skills, проекты и context switching.
7. Отвечай по-русски, кратко и для управленческого решения.
"""


class ArcAiService:
    def __init__(self) -> None:
        self.settings = get_settings()

    async def chat(self, request: ChatRequest, session: AsyncSession) -> ChatResponse:
        if not self.settings.nitec_llm_api_key:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "AI_NOT_CONFIGURED",
                    "message": "Добавьте новый NITEC_LLM_API_KEY в .env и перезапустите AI service.",
                },
            )

        conversation = await self._conversation(request, session)
        session.add(Message(conversation_id=conversation.id, role="user", content=request.message))
        conversation.updated_at = datetime.now(timezone.utc)
        await session.commit()

        history = await session.scalars(
            select(Message)
            .where(Message.conversation_id == conversation.id)
            .order_by(Message.created_at.desc(), Message.id.desc())
            .limit(24)
        )
        previous = list(reversed(history.all()))
        messages: list[dict[str, Any]] = [{"role": "system", "content": SYSTEM_PROMPT}]
        messages.extend({"role": item.role, "content": item.content} for item in previous)
        facts_used: list[FactSource] = []

        final_message = ""
        for _ in range(5):
            response = await self._completion(messages)
            choice = response.get("choices", [{}])[0].get("message", {})
            tool_calls = choice.get("tool_calls") or []
            if not tool_calls:
                final_message = (choice.get("content") or "").strip()
                break

            messages.append({
                "role": "assistant",
                "content": choice.get("content"),
                "tool_calls": tool_calls,
            })
            for tool_call in tool_calls:
                call_id = str(tool_call.get("id") or uuid.uuid4())
                function = tool_call.get("function") or {}
                name = str(function.get("name") or "")
                try:
                    arguments = json.loads(function.get("arguments") or "{}")
                    if not isinstance(arguments, dict):
                        raise ValueError("Tool arguments must be an object")
                except (json.JSONDecodeError, ValueError) as error:
                    arguments = {}
                    result: dict | list = {"error": str(error)}
                    await self._log_tool(session, conversation.id, call_id, name, arguments, result, 0, "INVALID_ARGUMENTS")
                else:
                    started = time.perf_counter()
                    try:
                        result = await execute_tool(name, arguments)
                        status = "SUCCESS"
                        facts_used.append(FactSource(tool=name, arguments=arguments))
                    except Exception as error:  # backend errors become explicit tool facts
                        result = {"error": str(error), "tool": name}
                        status = "ERROR"
                    duration_ms = int((time.perf_counter() - started) * 1000)
                    await self._log_tool(session, conversation.id, call_id, name, arguments, result, duration_ms, status)
                messages.append({
                    "role": "tool",
                    "tool_call_id": call_id,
                    "content": json.dumps(result, ensure_ascii=False, default=str),
                })

        if not final_message:
            raise HTTPException(status_code=502, detail="LLM did not produce a final answer")

        session.add(Message(conversation_id=conversation.id, role="assistant", content=final_message))
        conversation.updated_at = datetime.now(timezone.utc)
        await session.commit()
        return ChatResponse(
            conversation_id=conversation.id,
            message=final_message,
            facts_used=facts_used,
            model=self.settings.nitec_llm_model,
        )

    async def _conversation(self, request: ChatRequest, session: AsyncSession) -> Conversation:
        if request.conversation_id:
            conversation = await session.get(Conversation, request.conversation_id)
            if conversation is None:
                raise HTTPException(status_code=404, detail="Conversation not found")
            return conversation
        title = request.message.strip().replace("\n", " ")[:72]
        conversation = Conversation(title=title)
        session.add(conversation)
        await session.flush()
        return conversation

    async def _completion(self, messages: list[dict[str, Any]]) -> dict[str, Any]:
        payload = {
            "model": self.settings.nitec_llm_model,
            "messages": messages,
            "tools": TOOLS,
            "tool_choice": "auto",
            "temperature": self.settings.temperature,
            "max_tokens": self.settings.max_tokens,
        }
        timeout = httpx.Timeout(connect=5.0, read=120.0, write=120.0, pool=5.0)
        retry_statuses = {429, 502, 503, 504}
        async with httpx.AsyncClient(base_url=self.settings.nitec_llm_base_url.rstrip("/"), timeout=timeout) as client:
            for attempt in range(3):
                try:
                    response = await client.post(
                        "/chat/completions",
                        headers={"Authorization": f"Bearer {self.settings.nitec_llm_api_key}"},
                        json=payload,
                    )
                except (httpx.ConnectError, httpx.ReadTimeout) as error:
                    if attempt == 2:
                        raise HTTPException(status_code=503, detail="NITEC LLM is unavailable") from error
                    await asyncio.sleep(0.5 * (2 ** attempt))
                    continue
                if response.status_code in retry_statuses and attempt < 2:
                    await asyncio.sleep(0.5 * (2 ** attempt))
                    continue
                if response.is_error:
                    raise HTTPException(status_code=503, detail=f"NITEC LLM returned {response.status_code}")
                return response.json()
        raise HTTPException(status_code=503, detail="NITEC LLM is unavailable")

    async def _log_tool(
        self,
        session: AsyncSession,
        conversation_id: uuid.UUID,
        call_id: str,
        name: str,
        arguments: dict,
        result: dict | list,
        duration_ms: int,
        status: str,
    ) -> None:
        session.add(ToolCallLog(
            conversation_id=conversation_id,
            tool_call_id=call_id,
            tool_name=name,
            arguments=arguments,
            result=result,
            duration_ms=duration_ms,
            status=status,
        ))
        await session.commit()


async def seed_model_config(session: AsyncSession) -> None:
    existing = await session.scalar(select(ModelConfig).limit(1))
    if existing is None:
        settings = get_settings()
        session.add(ModelConfig(
            provider="NITEC",
            base_url=settings.nitec_llm_base_url,
            model=settings.nitec_llm_model,
            temperature=settings.temperature,
            max_tokens=settings.max_tokens,
            enabled=True,
        ))
        await session.commit()

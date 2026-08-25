from typing import Any
from urllib.parse import quote

import httpx

from .config import get_settings


TOOLS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "get_portfolio_health",
            "description": "Получить состояние всего портфеля и проекты, требующие внимания.",
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_project_health",
            "description": "Получить фактический Health Score проекта и его составляющие.",
            "parameters": {
                "type": "object",
                "properties": {"project": {"type": "string"}},
                "required": ["project"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_sprint_health",
            "description": "Получить фактические показатели активного спринта проекта.",
            "parameters": {
                "type": "object",
                "properties": {"project": {"type": "string"}},
                "required": ["project"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_people_load",
            "description": "Получить нагрузку сотрудников по проекту или всему портфелю.",
            "parameters": {
                "type": "object",
                "properties": {"project": {"type": ["string", "null"]}},
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_employee_load",
            "description": "Получить capacity, проекты и нагрузку конкретного сотрудника.",
            "parameters": {
                "type": "object",
                "properties": {"employee": {"type": "string"}},
                "required": ["employee"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_stuck_tasks",
            "description": "Получить задачи, находящиеся в текущем статусе дольше заданного числа дней.",
            "parameters": {
                "type": "object",
                "properties": {"project": {"type": "string"}, "min_days": {"type": "integer", "minimum": 1, "maximum": 90}},
                "required": ["project"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_blocked_tasks",
            "description": "Получить заблокированные задачи проекта.",
            "parameters": {
                "type": "object",
                "properties": {"project": {"type": "string"}},
                "required": ["project"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_release_readiness",
            "description": "Получить рассчитанную готовность проекта к релизу.",
            "parameters": {
                "type": "object",
                "properties": {"project": {"type": "string"}},
                "required": ["project"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_project_anomalies",
            "description": "Получить аномалии проекта относительно исторического снимка.",
            "parameters": {
                "type": "object",
                "properties": {"project": {"type": "string"}, "period_days": {"type": "integer", "minimum": 1, "maximum": 90}},
                "required": ["project"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_pm_quality",
            "description": "Получить объективные показатели Delivery Management проекта.",
            "parameters": {
                "type": "object",
                "properties": {"project": {"type": "string"}},
                "required": ["project"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "simulate_resource_move",
            "description": "Рассчитать what-if сценарий перевода части capacity сотрудника. Это только симуляция.",
            "parameters": {
                "type": "object",
                "properties": {
                    "employee": {"type": "string"},
                    "from_project": {"type": "string"},
                    "to_project": {"type": "string"},
                    "capacity_percent": {"type": "integer", "minimum": 10, "maximum": 100},
                },
                "required": ["employee", "from_project", "to_project", "capacity_percent"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "generate_resource_plan",
            "description": "Получить рассчитанный Analytics Engine план ресурсов на период.",
            "parameters": {
                "type": "object",
                "properties": {"period": {"type": "string", "enum": ["next_week", "next_sprint"]}},
                "required": ["period"],
                "additionalProperties": False,
            },
        },
    },
]


async def execute_tool(name: str, arguments: dict[str, Any]) -> dict | list:
    settings = get_settings()
    params: dict[str, Any] = {}
    if name == "get_portfolio_health":
        route = ("GET", "/api/portfolio/health")
    elif name == "get_project_health":
        route = ("GET", f"/api/projects/{project(arguments)}/health")
    elif name == "get_sprint_health":
        route = ("GET", f"/api/projects/{project(arguments)}/sprint")
    elif name == "get_people_load":
        route = ("GET", "/api/people")
        if arguments.get("project"):
            params["project"] = arguments["project"]
    elif name == "get_employee_load":
        employee = str(arguments.get("employee", "")).strip()
        if not employee:
            raise ValueError("employee is required")
        route = ("GET", f"/api/people/{quote(employee, safe='')}")
    elif name == "get_stuck_tasks":
        route = ("GET", f"/api/projects/{project(arguments)}/stuck")
        params["minDays"] = arguments.get("min_days", 3)
    elif name == "get_blocked_tasks":
        route = ("GET", f"/api/projects/{project(arguments)}/blocked")
    elif name == "get_release_readiness":
        route = ("GET", f"/api/projects/{project(arguments)}/release-readiness")
    elif name == "get_project_anomalies":
        route = ("GET", f"/api/projects/{project(arguments)}/anomalies")
        params["periodDays"] = arguments.get("period_days", 7)
    elif name == "get_pm_quality":
        route = ("GET", f"/api/projects/{project(arguments)}/delivery-management")
    elif name == "generate_resource_plan":
        route = ("GET", "/api/resource-plan")
        params["period"] = arguments.get("period", "next_week")
    elif name == "simulate_resource_move":
        route = ("POST", "/api/simulations/resource-move")
    else:
        raise ValueError(f"Unknown tool: {name}")

    async with httpx.AsyncClient(base_url=settings.arc_backend_url, timeout=125.0) as client:
        if route[0] == "POST":
            response = await client.post(route[1], json=arguments)
        else:
            response = await client.get(route[1], params=params)
        response.raise_for_status()
        return response.json()


def project(arguments: dict[str, Any]) -> str:
    value = str(arguments.get("project", "")).strip().upper()
    if not value or not all(character.isalnum() or character in "_-" for character in value):
        raise ValueError("A valid project key is required")
    return quote(value, safe="")
